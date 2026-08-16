package com.github.fanziyun.updater.screen

import com.github.fanziyun.updater.UpdaterService
import com.github.fanziyun.updater.handoff.RestartCapabilities
import com.github.fanziyun.updater.handoff.RestartMode
import com.github.fanziyun.updater.handoff.RestartProgressStage
import com.github.fanziyun.updater.handoff.RestartSession
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class RestartChoiceScreen(private val parentScreen: Screen?) :
    Screen(Component.translatable("screen.updater363.restart.title")) {
    private var capabilities: RestartCapabilities? = null
    private var session: RestartSession? = null
    private var error: String? = null
    private var allowLowMemory = false
    private val actionButtons = mutableListOf<Button>()

    override fun init() {
        super.init()
        capabilities = runCatching { UpdaterService.restartCapabilities() }
            .onFailure { error = it.message ?: "Unable to inspect restart support" }
            .getOrNull()
        val caps = capabilities
        val buttonWidth = 230
        val left = width / 2 - buttonWidth / 2
        var y = height / 2 - 28
        actionButtons.clear()
        if (UpdaterService.config.experimentalFastRestart && caps?.fastAvailable == true) {
            actionButtons += addRenderableWidget(
                Button.builder(Component.translatable("screen.updater363.restart.fast")) {
                    if (caps.lowMemory && !allowLowMemory) {
                        allowLowMemory = true
                        error = Component.translatable("screen.updater363.restart.low_memory").string
                        return@builder
                    }
                    start(RestartMode.FAST)
                }.bounds(left, y, buttonWidth, 20).build(),
            )
            y += 24
        }
        actionButtons += addRenderableWidget(
            Button.builder(Component.translatable("screen.updater363.restart.automatic")) { start(RestartMode.AUTOMATIC) }
                .bounds(left, y, buttonWidth, 20).build().also { it.active = caps?.automaticAvailable == true },
        )
        y += 24
        actionButtons += addRenderableWidget(
            Button.builder(Component.translatable("screen.updater363.restart.deferred")) { start(RestartMode.DEFERRED) }
                .bounds(left, y, buttonWidth, 20).build().also { it.active = caps?.deferredAvailable == true },
        )
        addRenderableWidget(
            Button.builder(Component.translatable("gui.back")) { onClose() }
                .bounds(width / 2 - 100, height - 35, 200, 20).build(),
        )
    }

    override fun tick() {
        super.tick()
        val current = session ?: return
        if (current.progress.stage == RestartProgressStage.FAILED) {
            error = current.progress.message
            session = null
            actionButtons.forEach { it.active = true }
            capabilities?.let { caps ->
                val automaticIndex = if (UpdaterService.config.experimentalFastRestart && caps.fastAvailable) 1 else 0
                actionButtons.getOrNull(automaticIndex)?.active = caps.automaticAvailable
                actionButtons.lastOrNull()?.active = caps.deferredAvailable
            }
        }
    }

    override fun onClose() {
        if (session == null) ClientScreens.set(parentScreen)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        val heading = title.string
        graphics.drawString(font, heading, (width - font.width(heading)) / 2, 28, 0xFFFFFFFF.toInt())
        val message = if (session == null) Component.translatable("screen.updater363.restart.choose").string
        else Component.translatable(progressKey(requireNotNull(session).progress.stage)).string
        graphics.drawString(font, fit(message, width - 50), 25, 65, 0xFFCCCCCC.toInt())
        error?.let { graphics.drawString(font, fit(it, width - 50), 25, 88, 0xFFFFAA00.toInt()) }
        if (capabilities?.automaticAvailable == false && session == null) {
            graphics.drawString(font, fit(capabilities?.automaticReason.orEmpty(), width - 50), 25, 110, 0xFFAAAAAA.toInt())
        } else if (UpdaterService.config.experimentalFastRestart && capabilities?.fastAvailable == false && session == null) {
            graphics.drawString(font, fit(capabilities?.fastReason.orEmpty(), width - 50), 25, 110, 0xFFAAAAAA.toInt())
        }
    }

    private fun start(mode: RestartMode) {
        error = null
        actionButtons.forEach { it.active = false }
        session = UpdaterService.startRestart(mode, allowLowMemory)
    }

    private fun progressKey(stage: RestartProgressStage): String = when (stage) {
        RestartProgressStage.PREPARING -> "screen.updater363.restart.preparing"
        RestartProgressStage.WAITING_FOR_CHILD -> "screen.updater363.restart.child_starting"
        RestartProgressStage.FIRST_FRAME -> "screen.updater363.restart.first_frame"
        RestartProgressStage.STABILIZING -> "screen.updater363.restart.stabilizing"
        RestartProgressStage.READY -> "screen.updater363.restart.ready"
        RestartProgressStage.TAKING_OVER -> "screen.updater363.restart.taking_over"
        RestartProgressStage.WAITING_FOR_EXIT -> "screen.updater363.restart.waiting_exit"
        RestartProgressStage.FAILED -> "screen.updater363.restart.failed"
    }

    private fun fit(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        val suffix = "..."
        var end = text.length
        while (end > 0 && font.width(text.substring(0, end) + suffix) > maxWidth) end--
        return text.substring(0, end) + suffix
    }
}
