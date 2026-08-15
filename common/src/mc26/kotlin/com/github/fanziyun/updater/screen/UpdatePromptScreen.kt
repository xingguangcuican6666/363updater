package com.github.fanziyun.updater.screen

import com.github.fanziyun.updater.UpdaterService
import com.github.fanziyun.updater.data.BackupManager
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class UpdatePromptScreen(private val parentScreen: Screen?) :
    Screen(Component.translatable("screen.updater363.prompt.title")) {

    override fun init() {
        super.init()
        val width = 210
        val left = this.width / 2 - width / 2
        val y = this.height / 2 + 20
        addRenderableWidget(
            Button.builder(Component.translatable("screen.updater363.view_diff")) {
                ClientScreens.set(UpdateDiffScreen(this))
            }.bounds(left, y, width, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("screen.updater363.update_now")) {
                ClientScreens.set(UpdateDiffScreen(this, applyAfterLoad = true))
            }.bounds(left, y + 24, width, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("screen.updater363.ignore")) {
                UpdaterService.ignore()
                onClose()
            }.bounds(left, y + 48, width, 20).build()
        )
        val rollback = addRenderableWidget(
            Button.builder(Component.translatable("screen.updater363.rollback")) {
                UpdaterService.rollback().whenComplete { _, exception ->
                    ClientScreens.execute {
                        if (ClientScreens.current() !== this@UpdatePromptScreen) return@execute
                        val failures = exception?.let { listOf(it.cause?.message ?: it.message ?: "Rollback failed") }.orEmpty()
                        ClientScreens.set(UpdateResultScreen(parentScreen, failures, rollback = true))
                    }
                }
            }.bounds(left, y + 72, width, 20).build()
        )
        rollback.active = runCatching { BackupManager.latest() != null }.getOrDefault(false)
    }

    override fun onClose() {
        ClientScreens.set(parentScreen)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val title = title.string
        graphics.text(font, title, (width - font.width(title)) / 2, height / 2 - 50, 0xFFFFFFFF.toInt())
        val state = UpdaterService.state
        val message = Component.translatable(
            "screen.updater363.prompt.message",
            state.currentVersion,
            state.targetVersion,
        ).string
        graphics.text(font, message, (width - font.width(message)) / 2, height / 2 - 25, 0xFFCCCCCC.toInt())
    }
}
