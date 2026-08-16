package com.github.fanziyun.updater.handoff

import com.github.fanziyun.updater.UpdaterService
import com.github.fanziyun.updater.screen.ClientScreens
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

object MinecraftFastRestartClient : FastRestartClientAdapter {
    override fun captureOldClient(trim: Boolean): RestartWindowState = FastRestartClients.onClientThread {
        val minecraft = Minecraft.getInstance()
        check(minecraft.level == null) { "Fast restart can only be started from the main menu" }
        val window = minecraft.window
        val previousLimit = if (trim) minecraft.options.framerateLimit().get() else null
        if (trim) {
            minecraft.soundManager.stop()
            if (previousLimit != null && previousLimit > 30) minecraft.options.framerateLimit().set(30)
            UpdaterService.trimForRestart()
            System.gc()
        }
        RestartWindowState(
            window.x,
            window.y,
            window.screenWidth,
            window.screenHeight,
            window.isFullscreen,
            previousLimit,
        )
    }

    override fun restoreOldClient(state: RestartWindowState?) {
        val limit = state?.previousFpsLimit ?: return
        runCatching { FastRestartClients.onClientThread { Minecraft.getInstance().options.framerateLimit().set(limit) } }
    }

    override fun stopOldClient() {
        ClientScreens.execute {
            val minecraft = Minecraft.getInstance()
            minecraft.soundManager.stop()
            GLFW.glfwHideWindow(minecraft.window.window)
            minecraft.stop()
        }
    }

    override fun showChild(request: ShowWindowRequest) {
        val window = Minecraft.getInstance().window
        window.setWindowed(request.width, request.height)
        GLFW.glfwSetWindowPos(window.window, request.x, request.y)
        if (request.fullscreen && !window.isFullscreen) {
            window.toggleFullScreen()
            window.updateDisplay()
        }
        GLFW.glfwShowWindow(window.window)
        GLFW.glfwFocusWindow(window.window)
    }
}
