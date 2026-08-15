package com.github.fanziyun.updater.screen

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class UpdateResultScreen(
    private val parentScreen: Screen?,
    private val reloadFailures: List<String>,
    private val rollback: Boolean = false,
) : Screen(Component.translatable("screen.updater363.result.title")) {
    override fun init() {
        super.init()
        addRenderableWidget(
            Button.builder(Component.translatable("gui.done")) { onClose() }
                .bounds(width / 2 - 100, height - 45, 200, 20)
                .build()
        )
    }

    override fun onClose() {
        ClientScreens.set(parentScreen)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val title = this.title.string
        graphics.text(font, title, (width - font.width(title)) / 2, 30, 0xFFFFFFFF.toInt())
        val lines = when {
            rollback && reloadFailures.isEmpty() -> listOf(Component.translatable("screen.updater363.rollback.success").string)
            reloadFailures.isEmpty() -> listOf(Component.translatable("screen.updater363.result.success").string)
            else -> listOf(Component.translatable("screen.updater363.result.restart").string) + reloadFailures
        }
        lines.forEachIndexed { index, line ->
            graphics.text(font, line.take(120), 20, 70 + index * 16, if (reloadFailures.isEmpty()) 0xFF55FF55.toInt() else 0xFFFFAA00.toInt())
        }
    }
}
