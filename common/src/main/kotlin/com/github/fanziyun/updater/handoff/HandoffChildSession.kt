package com.github.fanziyun.updater.handoff

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.UpdaterService
import com.github.fanziyun.updater.transaction.TransactionStage
import com.github.fanziyun.updater.transaction.UpdateTransactionManager
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class ChildHandoffStage {
    INACTIVE,
    CONNECTING,
    STARTING,
    FIRST_FRAME,
    STABILIZING,
    READY,
    SHOWING,
    WAITING_FOR_OLD_EXIT,
    COMMITTING,
    COMMITTED,
    COMMIT_FAILED,
    DEFERRED,
    FAILED,
}

data class ShowWindowRequest(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val fullscreen: Boolean,
)

object HandoffChildSession {
    private val environment = System.getenv()
    val active: Boolean = environment[HandoffProtocol.ENV_ACTIVE] == "1"
    val transactionId: String? = environment[HandoffProtocol.ENV_TRANSACTION]?.takeIf(String::isNotBlank)
    val oldPid: Long = environment[HandoffProtocol.ENV_OLD_PID]?.toLongOrNull() ?: -1L

    private val port = environment[HandoffProtocol.ENV_PORT]?.toIntOrNull() ?: -1
    private val token = environment[HandoffProtocol.ENV_TOKEN].orEmpty()
    private val launchedAtMillis = environment[HandoffProtocol.ENV_STARTED_EPOCH_MS]?.toLongOrNull()
        ?: System.currentTimeMillis()
    private val started = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val titleReady = AtomicBoolean(false)
    private val firstFrame = AtomicBoolean(false)
    private val stable = AtomicBoolean(false)
    private val visibleFrame = AtomicBoolean(false)
    private val committing = AtomicBoolean(false)
    private val allowTitle = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0L)
    private val firstFrameNanos = AtomicLong(0L)
    private val shownAtFrame = AtomicLong(-1L)
    private val visibleAtMillis = AtomicLong(0L)
    private val stageRef = AtomicReference(if (active) ChildHandoffStage.CONNECTING else ChildHandoffStage.INACTIVE)
    private val errorRef = AtomicReference<String?>(null)
    private val showRequest = AtomicReference<ShowWindowRequest?>(null)
    private val loaderId = AtomicReference("")
    private val socketRef = AtomicReference<Socket?>(null)
    private val outputLock = Any()
    private val scheduler = Executors.newScheduledThreadPool(2) { task ->
        Thread(task, "363Updater-ChildHandoff").apply { isDaemon = true }
    }

    val stage: ChildHandoffStage get() = stageRef.get()
    val error: String? get() = errorRef.get()
    val shouldInterceptTitle: Boolean get() = active && !allowTitle.get()

    fun start(loader: String) {
        if (!active || !started.compareAndSet(false, true)) return
        loaderId.set(loader)
        require(port in 1..65535 && token.length >= 16 && !transactionId.isNullOrBlank()) {
            "Invalid child handoff environment"
        }
        scheduler.execute(::connect)
    }

    fun markTitleReady() {
        if (!active) return
        titleReady.set(true)
        stageRef.compareAndSet(ChildHandoffStage.STARTING, ChildHandoffStage.STABILIZING)
    }

    /** Called after a frame has been presented by the active client render loop. */
    fun onFramePresented() {
        if (!active || !connected.get() || !titleReady.get()) return
        val frame = frameCounter.incrementAndGet()
        val now = System.nanoTime()
        if (firstFrame.compareAndSet(false, true)) {
            firstFrameNanos.set(now)
            stageRef.set(ChildHandoffStage.FIRST_FRAME)
            if (!transition(TransactionStage.FIRST_FRAME)) return
            send(HandoffProtocol.Message.of(HandoffProtocol.Type.FIRST_FRAME))
            stageRef.set(ChildHandoffStage.STABILIZING)
        }
        if (!stable.get() && now - firstFrameNanos.get() >= STABLE_NANOS && stable.compareAndSet(false, true)) {
            if (!transition(TransactionStage.STABLE)) return
            send(HandoffProtocol.Message.of(HandoffProtocol.Type.STABLE))
            if (!transition(TransactionStage.READY)) return
            stageRef.set(ChildHandoffStage.READY)
            send(HandoffProtocol.Message.of(HandoffProtocol.Type.READY))
        }
        val shownFrame = shownAtFrame.get()
        if (shownFrame >= 0L && frame > shownFrame && visibleFrame.compareAndSet(false, true)) {
            visibleAtMillis.set(System.currentTimeMillis())
            if (!transition(TransactionStage.OLD_STOPPING)) return
            send(HandoffProtocol.Message.of(HandoffProtocol.Type.VISIBLE_FRAME))
            retryCommit()
        }
    }

    fun claimShowRequest(): ShowWindowRequest? = showRequest.getAndSet(null)

    fun markWindowShown() {
        shownAtFrame.compareAndSet(-1L, frameCounter.get())
        stageRef.set(ChildHandoffStage.SHOWING)
    }

    fun retryCommit() {
        val id = transactionId ?: return
        if (!committing.compareAndSet(false, true)) return
        errorRef.set(null)
        continueCommit(id)
    }

    private fun continueCommit(id: String) {
        if (FastRestartPlatformPolicy.shouldWaitForOldProcessBeforeCommit(
                System.getProperty("os.name", ""),
                oldProcessAlive(),
            )
        ) {
            stageRef.set(ChildHandoffStage.WAITING_FOR_OLD_EXIT)
            scheduler.schedule({ continueCommit(id) }, OLD_EXIT_POLL_MS, TimeUnit.MILLISECONDS)
            return
        }
        stageRef.set(ChildHandoffStage.COMMITTING)
        send(HandoffProtocol.Message.of(HandoffProtocol.Type.COMMITTING))
        UpdaterService.commitTransaction(id).whenComplete { _, exception ->
            committing.set(false)
            if (exception == null) {
                stageRef.set(ChildHandoffStage.COMMITTED)
                send(HandoffProtocol.Message.of(HandoffProtocol.Type.COMMITTED))
            } else {
                errorRef.set(exception.cause?.message ?: exception.message ?: "Commit failed")
                stageRef.set(ChildHandoffStage.COMMIT_FAILED)
                send(HandoffProtocol.Message(
                    HandoffProtocol.Type.COMMIT_FAILED,
                    0L,
                    0L,
                    0L,
                    0L,
                    false,
                    errorRef.get()?.take(500).orEmpty(),
                ))
            }
        }
    }

    fun deferCommit() {
        if (stage != ChildHandoffStage.COMMIT_FAILED) return
        stageRef.set(ChildHandoffStage.DEFERRED)
    }

    fun oldProcessAlive(): Boolean = oldPid > 0L &&
        ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false)

    fun canLeaveHandoff(): Boolean = stage in setOf(ChildHandoffStage.COMMITTED, ChildHandoffStage.DEFERRED) &&
        !oldProcessAlive()

    fun releaseToTitle() {
        if (canLeaveHandoff()) allowTitle.set(true)
    }

    fun oldProcessNeedsForceButton(): Boolean {
        val visible = visibleAtMillis.get()
        return visible > 0L && System.currentTimeMillis() - visible >= OLD_EXIT_GRACE_MS &&
            oldProcessAlive()
    }

    fun terminateOldProcess() {
        val process = ProcessHandle.of(oldPid).orElse(null) ?: return
        if (!process.isAlive) return
        process.destroy()
        scheduler.schedule({ if (process.isAlive) process.destroyForcibly() }, FORCE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun connect() {
        val deadline = System.nanoTime() + CONNECT_TIMEOUT_NANOS
        while (!connected.get() && System.nanoTime() < deadline) {
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
                socketRef.set(socket)
                connected.set(true)
                stageRef.set(ChildHandoffStage.STARTING)
                send(HandoffProtocol.Message(
                    HandoffProtocol.Type.HELLO_CHILD,
                    ProcessHandle.current().pid(),
                    0L,
                    0L,
                    0L,
                    false,
                    transactionId.orEmpty(),
                ))
                StagedModsAgent.validationError(loaderId.get())?.let { validationError ->
                    send(HandoffProtocol.Message(
                        HandoffProtocol.Type.ABORT,
                        ProcessHandle.current().pid(),
                        0L,
                        0L,
                        0L,
                        false,
                        validationError,
                    ))
                    fail(validationError)
                    return
                }
                scheduler.scheduleAtFixedRate(::heartbeat, 0L, 1L, TimeUnit.SECONDS)
                scheduler.execute { listen(socket) }
                return
            } catch (_: Exception) {
                Thread.sleep(200L)
            }
        }
        fail("Unable to connect to the old client")
    }

    private fun listen(socket: Socket) {
        try {
            while (!socket.isClosed) {
                when (val message = HandoffProtocol.read(socket.getInputStream(), token)) {
                    else -> when (message.type()) {
                        HandoffProtocol.Type.SHOW -> showRequest.set(ShowWindowRequest(
                            message.first().toInt(),
                            message.second().toInt(),
                            message.third().toInt().coerceAtLeast(320),
                            message.fourth().toInt().coerceAtLeast(240),
                            message.flag(),
                        ))
                        HandoffProtocol.Type.ABORT, HandoffProtocol.Type.SHUTDOWN -> fail(
                            message.text().ifBlank { "The old client cancelled the handoff" },
                        )
                        else -> Unit
                    }
                }
            }
        } catch (exception: Exception) {
            if (!visibleFrame.get()) fail(exception.message ?: "Handoff connection closed")
        }
    }

    private fun heartbeat() {
        if (!connected.get()) return
        val rss = LinuxProcessMetrics.rssBytes(ProcessHandle.current().pid()).orElse(0L)
        send(HandoffProtocol.Message(
            HandoffProtocol.Type.HEARTBEAT,
            ProcessHandle.current().pid(),
            rss,
            System.currentTimeMillis() - launchedAtMillis,
            0L,
            false,
            "",
        ))
    }

    private fun send(message: HandoffProtocol.Message) {
        val socket = socketRef.get() ?: return
        synchronized(outputLock) {
            runCatching { HandoffProtocol.write(socket.getOutputStream(), token, message) }
                .onFailure { if (!visibleFrame.get()) fail(it.message ?: "Unable to send handoff status") }
        }
    }

    private fun transition(next: TransactionStage): Boolean {
        val id = transactionId ?: return false
        return runCatching {
            val manager = UpdateTransactionManager()
            var transaction = manager.load(id)
            if (transaction.record.stage == TransactionStage.PREPARED && next == TransactionStage.FIRST_FRAME) {
                transaction = manager.transition(transaction, TransactionStage.CHILD_STARTING)
            }
            if (transaction.record.stage != next) manager.transition(transaction, next)
            true
        }.getOrElse {
            fail(it.message ?: "Unable to persist handoff stage")
            false
        }
    }

    private fun fail(message: String) {
        errorRef.compareAndSet(null, message.take(500))
        stageRef.set(ChildHandoffStage.FAILED)
        connected.set(false)
    }

    private const val STABLE_NANOS = 3_000_000_000L
    private const val CONNECT_TIMEOUT_NANOS = 15_000_000_000L
    private const val OLD_EXIT_GRACE_MS = 20_000L
    private const val OLD_EXIT_POLL_MS = 100L
    private const val FORCE_DELAY_MS = 5_000L
}
