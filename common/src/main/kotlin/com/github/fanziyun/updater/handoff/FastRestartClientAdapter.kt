package com.github.fanziyun.updater.handoff

import com.github.fanziyun.updater.screen.ClientScreens
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class RestartWindowState(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val fullscreen: Boolean,
    val previousFpsLimit: Int?,
)

interface FastRestartClientAdapter {
    fun captureOldClient(trim: Boolean): RestartWindowState
    fun restoreOldClient(state: RestartWindowState?)
    fun stopOldClient()
    fun showChild(request: ShowWindowRequest)
}

object FastRestartClients {
    val instance: FastRestartClientAdapter by lazy {
        val type = Class.forName("com.github.fanziyun.updater.handoff.MinecraftFastRestartClient")
        type.getField("INSTANCE").get(null) as FastRestartClientAdapter
    }

    fun <T> onClientThread(action: () -> T): T {
        val future = CompletableFuture<T>()
        ClientScreens.execute {
            runCatching(action).fold(future::complete, future::completeExceptionally)
        }
        return future.get(10L, TimeUnit.SECONDS)
    }
}
