package com.github.fanziyun.updater.handoff

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class HandoffEvent(val message: HandoffProtocol.Message, val childConnection: Boolean = false)

class HandoffServer : AutoCloseable {
    private val tokenValue = ByteArray(32).also(SecureRandom()::nextBytes)
    private val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenValue)
    private val socket = ServerSocket().apply {
        reuseAddress = false
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    }
    private val workers: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "363Updater-Handoff").apply { isDaemon = true }
    }
    private val events = LinkedBlockingQueue<HandoffEvent>()
    private val closed = AtomicBoolean(false)
    private val childOutput = AtomicReference<java.io.OutputStream?>(null)
    private val childLock = Any()
    private val connections = ConcurrentHashMap.newKeySet<Socket>()

    val port: Int get() = socket.localPort
    val authenticationToken: String get() = token

    init {
        workers.execute {
            while (!closed.get()) {
                try {
                    val connection = socket.accept()
                    if (!connection.inetAddress.isLoopbackAddress) {
                        connection.close()
                    } else {
                        connection.soTimeout = 10_000
                        connections += connection
                        workers.execute { handle(connection) }
                    }
                } catch (_: SocketException) {
                    if (!closed.get()) events.offer(HandoffEvent(HandoffProtocol.Message.of(HandoffProtocol.Type.ABORT)))
                } catch (exception: Exception) {
                    if (!closed.get()) {
                        events.offer(HandoffEvent(HandoffProtocol.Message(
                            HandoffProtocol.Type.ABORT,
                            0L,
                            0L,
                            0L,
                            0L,
                            false,
                            exception.message?.take(500) ?: "Handoff listener failed",
                        )))
                    }
                }
            }
        }
    }

    fun await(timeoutMs: Long): HandoffEvent? = events.poll(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)

    fun send(
        type: HandoffProtocol.Type,
        first: Long = 0L,
        second: Long = 0L,
        third: Long = 0L,
        fourth: Long = 0L,
        flag: Boolean = false,
        text: String = "",
    ) {
        val output = childOutput.get() ?: return
        synchronized(childLock) {
            runCatching {
                HandoffProtocol.write(
                    output,
                    token,
                    HandoffProtocol.Message(type, first, second, third, fourth, flag, text),
                )
            }.onFailure { childOutput.compareAndSet(output, null) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        synchronized(childLock) { childOutput.set(null) }
        connections.forEach { runCatching { it.close() } }
        connections.clear()
        workers.shutdownNow()
    }

    private fun handle(connection: Socket) {
        var isChild = false
        var registeredOutput: java.io.OutputStream? = null
        try {
            connection.tcpNoDelay = true
            val input = connection.getInputStream()
            val output = connection.getOutputStream()
            while (!closed.get()) {
                val message = HandoffProtocol.read(input, token)
                if (message.type() == HandoffProtocol.Type.HELLO_CHILD) {
                    isChild = true
                    childOutput.set(output)
                    registeredOutput = output
                }
                events.offer(HandoffEvent(message, isChild))
            }
        } catch (_: Exception) {
            if (isChild && !closed.get()) {
                registeredOutput?.let { childOutput.compareAndSet(it, null) }
                events.offer(HandoffEvent(HandoffProtocol.Message(
                    HandoffProtocol.Type.ABORT,
                    0L,
                    0L,
                    0L,
                    0L,
                    false,
                    "Child handoff connection closed",
                ), true))
            }
        } finally {
            registeredOutput?.let { childOutput.compareAndSet(it, null) }
            connections.remove(connection)
            runCatching { connection.close() }
        }
    }
}
