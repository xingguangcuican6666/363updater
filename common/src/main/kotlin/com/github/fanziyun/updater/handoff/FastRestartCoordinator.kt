package com.github.fanziyun.updater.handoff

import com.github.fanziyun.updater.config.UpdaterConfig
import com.github.fanziyun.updater.data.LaunchMetrics
import com.github.fanziyun.updater.data.VersionTracker
import com.github.fanziyun.updater.platform.Platform
import com.github.fanziyun.updater.transaction.PreparedTransaction
import com.github.fanziyun.updater.transaction.TransactionStage
import com.github.fanziyun.updater.transaction.UpdateTransactionManager

object FastRestartCoordinator {
    @JvmStatic
    fun start(transaction: PreparedTransaction, config: UpdaterConfig, session: RestartSession) {
        val manager = UpdateTransactionManager()
        val client = FastRestartClients.instance
        var current = transaction
        var server: HandoffServer? = null
        var helper: Process? = null
        var childPid = -1L
        var oldState: RestartWindowState? = null
        var takeover = false
        try {
            check(current.record.stage == TransactionStage.PREPARED) {
                "Fast restart requires a prepared transaction"
            }
            oldState = client.captureOldClient(config.trimOldProcessDuringRestart)
            current = manager.activateConfiguration(current)
            current = manager.transition(current, TransactionStage.CHILD_STARTING)
            server = HandoffServer()
            val helperJar = current.helperJar ?: error("Missing copied updater helper JAR")
            val command = StagedModsLaunchStrategy.prepare(
                CurrentJvmCommand.capture(),
                Platform.INSTANCE.loaderId,
                current.generationMods,
                helperJar,
            )
            val request = HelperLaunchRequest(
                HelperLaunchRequest.Mode.FAST,
                ProcessHandle.current().pid(),
                server.port,
                server.authenticationToken,
                current.id,
                current.path,
                Platform.INSTANCE.gameDir,
                command,
            )
            helper = HelperProcessLauncher.start(helperJar, request)
            session.update(RestartProgress(RestartProgressStage.WAITING_FOR_CHILD, "Starting replacement client"))

            val metrics = VersionTracker.readInstallation(
                current.record.project,
                current.record.minecraftVersion,
                current.record.loader,
            )?.metrics ?: LaunchMetrics()
            val timeoutMs = RestartLaunchPolicy.timeoutMillis(metrics)
            val launchedAt = System.currentTimeMillis()
            var lastHeartbeat = 0L
            var childRss = 0L
            var peakCombinedRss = 0L
            while (System.currentTimeMillis() - launchedAt < timeoutMs) {
                val now = System.currentTimeMillis()
                val event = server.await(1_000L)
                val oldRss = LinuxProcessMetrics.rssBytes(ProcessHandle.current().pid()).orElse(0L)
                peakCombinedRss = maxOf(peakCombinedRss, oldRss + childRss)
                if (event == null) {
                    if (lastHeartbeat > 0L && now - lastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                        error("Replacement client heartbeat timed out")
                    }
                    continue
                }
                val message = event.message
                when (message.type()) {
                    HandoffProtocol.Type.HELLO_HELPER -> {
                        childPid = validateChildPid(childPid, message.first())
                        session.update(session.progress.copy(childPid = childPid))
                    }
                    HandoffProtocol.Type.HELLO_CHILD -> {
                        childPid = validateChildPid(childPid, message.first())
                        lastHeartbeat = now
                        session.update(RestartProgress(
                            RestartProgressStage.WAITING_FOR_CHILD,
                            "Replacement client connected",
                            childPid,
                            now - launchedAt,
                        ))
                    }
                    HandoffProtocol.Type.HEARTBEAT -> {
                        childPid = validateChildPid(childPid, message.first())
                        childRss = message.second().coerceAtLeast(0L)
                        lastHeartbeat = now
                        peakCombinedRss = maxOf(peakCombinedRss, oldRss + childRss)
                    }
                    HandoffProtocol.Type.FIRST_FRAME -> session.update(RestartProgress(
                        RestartProgressStage.FIRST_FRAME,
                        "Replacement client rendered its first frame",
                        childPid,
                        now - launchedAt,
                    ))
                    HandoffProtocol.Type.STABLE -> session.update(RestartProgress(
                        RestartProgressStage.STABILIZING,
                        "Verifying replacement client stability",
                        childPid,
                        now - launchedAt,
                    ))
                    HandoffProtocol.Type.READY -> {
                        session.update(RestartProgress(
                            RestartProgressStage.READY,
                            "Replacement client is ready",
                            childPid,
                            now - launchedAt,
                        ))
                        manager.recordMetrics(manager.load(current.id), now - launchedAt, peakCombinedRss)
                        val state = requireNotNull(oldState)
                        server.send(
                            HandoffProtocol.Type.SHOW,
                            state.x.toLong(),
                            state.y.toLong(),
                            state.width.toLong(),
                            state.height.toLong(),
                            state.fullscreen,
                        )
                    }
                    HandoffProtocol.Type.VISIBLE_FRAME -> {
                        takeover = true
                        val elapsed = now - launchedAt
                        manager.recordMetrics(manager.load(current.id), elapsed, peakCombinedRss)
                        session.update(RestartProgress(
                            RestartProgressStage.TAKING_OVER,
                            "Replacement window took over",
                            childPid,
                            elapsed,
                        ))
                        client.stopOldClient()
                        session.completion.complete(Unit)
                        return
                    }
                    HandoffProtocol.Type.CHILD_EXIT -> error(
                        "Replacement client exited before takeover (exit code ${message.second()})",
                    )
                    HandoffProtocol.Type.ABORT -> error(message.text().ifBlank { "Replacement client disconnected" })
                    else -> Unit
                }
            }
            error("Replacement client startup timed out")
        } catch (exception: Exception) {
            if (!takeover) {
                server?.send(HandoffProtocol.Type.ABORT, text = "Fast restart cancelled")
                terminate(childPid)
                helper?.takeIf(Process::isAlive)?.destroy()
                runCatching { manager.rollbackBeforeReady(manager.load(current.id)) }
                client.restoreOldClient(oldState)
            }
            session.fail(exception.message ?: "Fast restart failed", exception)
        } finally {
            server?.close()
        }
    }

    private fun validateChildPid(current: Long, next: Long): Long {
        require(next > 0L) { "Invalid replacement client process id" }
        require(current <= 0L || current == next) { "Restart helper and child reported different process ids" }
        return next
    }

    private fun terminate(pid: Long) {
        val process = ProcessHandle.of(pid).orElse(null) ?: return
        if (!process.isAlive) return
        process.destroy()
        val deadline = System.nanoTime() + FORCE_DELAY_NANOS
        while (process.isAlive && System.nanoTime() < deadline) Thread.sleep(100L)
        if (process.isAlive) process.destroyForcibly()
    }

    private const val HEARTBEAT_TIMEOUT_MS = 5_000L
    private const val FORCE_DELAY_NANOS = 5_000_000_000L
}
