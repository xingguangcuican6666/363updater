package com.github.fanziyun.updater.screen

import com.github.fanziyun.updater.data.ApplyResult
import com.github.fanziyun.updater.progress.ProgressViewModel
import com.github.fanziyun.updater.progress.UpdateProgressTracker
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.concurrent.CompletableFuture

/**
 * Progress page shown between the difference view and the result/restart choice.
 *
 * It polls [UpdateProgressTracker] every tick and hands the snapshot to the shared [ProgressLayout],
 * which draws the headline (正在更新：<file>), the per-file caption and bar (进度：xx / xx, 条1), and
 * the overall caption and bar (总进度：xx / xx, 条2). Closing is blocked while a run is in flight; a
 * failure keeps the page open with the error and a back button.
 */
class UpdateProgressScreen(
    private val parentScreen: Screen?,
    private val future: CompletableFuture<ApplyResult>,
    private val onSuccess: (ApplyResult) -> Unit,
) : Screen(Component.translatable("screen.updater363.progress.title")) {
    private var backButton: Button? = null
    private var handled = false
    private var errorMessage: String? = null

    override fun init() {
        super.init()
        backButton = addRenderableWidget(
            Button.builder(Component.translatable("gui.back")) { ClientScreens.set(parentScreen) }
                .bounds(width / 2 - 100, height - 35, 200, 20)
                .build(),
        ).also { it.visible = false }
    }

    override fun tick() {
        super.tick()
        backButton?.visible = errorMessage != null
        if (handled || !future.isDone) return
        handled = true
        val outcome = runCatching { future.get() }
        outcome.onSuccess { onSuccess(it) }.onFailure { failure ->
            val cause = failure.cause ?: failure
            errorMessage = cause.message ?: cause.javaClass.simpleName
            UpdateProgressTracker.failed(errorMessage ?: "")
        }
    }

    override fun onClose() {
        // Gate on the apply future, not the tracker phase: the tracker may still read DONE from the
        // preview when this page opens, which would let the player leave mid-write.
        if (!future.isDone) return
        ClientScreens.set(parentScreen)
    }

    override fun shouldCloseOnEsc(): Boolean = future.isDone

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        val model = ProgressViewModel.of(UpdateProgressTracker.progress)
        // The tracker carries the failure message, so the shared layout draws it on the status line;
        // drawing it again here would show the same text twice.
        ProgressLayout.draw(GraphicsCanvas(graphics, font, width, height), model, ::localize, title.string)
    }

    private fun localize(key: String, arg: Any): String =
        if (arg is String && arg.isEmpty()) Component.translatable(key).string
        else Component.translatable(key, arg).string

    private class GraphicsCanvas(
        private val graphics: GuiGraphics,
        private val font: Font,
        override val canvasWidth: Int,
        override val canvasHeight: Int,
    ) : ProgressCanvas {
        override fun textWidth(value: String): Int = font.width(value)
        override fun drawText(value: String, x: Int, y: Int, color: Int) {
            graphics.drawString(font, value, x, y, color)
        }
        override fun fill(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
            graphics.fill(x1, y1, x2, y2, color)
        }
    }
}
