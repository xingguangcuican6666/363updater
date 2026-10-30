package com.github.fanziyun.updater.screen

import com.github.fanziyun.updater.UpdaterService
import com.github.fanziyun.updater.merge.FileAction
import com.github.fanziyun.updater.progress.ProgressViewModel
import com.github.fanziyun.updater.progress.UpdateProgressTracker
import com.github.fanziyun.updater.merge.KeyAction
import com.github.fanziyun.updater.merge.UpdatePlan
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class UpdateDiffScreen(
    private val parentScreen: Screen?,
    private val applyAfterLoad: Boolean = false,
) : Screen(Component.translatable("screen.updater363.diff.title")) {
    private var plan: UpdatePlan? = null
    private var error: String? = null
    private var updateButton: Button? = null
    private var loading = true
    private var detailLines: List<RenderLine> = emptyList()
    private val scrollbar = DiffScrollbarState()
    private var codeConfirmed = false

    private data class RenderLine(val text: String, val color: Int)

    override fun init() {
        super.init()
        val buttonWidth = 110
        val gap = 6
        val left = width / 2 - buttonWidth - gap / 2
        val y = height - 35
        updateButton = addRenderableWidget(
            Button.builder(Component.translatable("screen.updater363.apply")) { applyUpdate() }
                .bounds(left, y, buttonWidth, 20)
                .build()
                .also { it.active = false },
        )
        addRenderableWidget(
            Button.builder(Component.translatable("gui.back")) { onClose() }
                .bounds(width / 2 + gap / 2, y, buttonWidth, 20)
                .build(),
        )
        UpdaterService.preview().whenComplete { result, exception ->
            ClientScreens.execute {
                if (ClientScreens.current() !== this@UpdateDiffScreen) return@execute
                loading = false
                if (exception != null) error = exception.cause?.message ?: exception.message
                    ?: Component.translatable("screen.updater363.unknown_error").string
                else {
                    plan = result
                    detailLines = buildLines(result)
                    scrollbar.update(detailLines.size * 12, height - 90)
                    updateButton?.active = !result.hasConflicts && result.changedFiles.isNotEmpty()
                    if (applyAfterLoad && !result.codeChanges && !result.hasConflicts) applyUpdate()
                }
            }
        }
    }

    private fun applyUpdate() {
        val currentPlan = plan ?: return
        if (currentPlan.hasConflicts) return
        if (currentPlan.codeChanges && !codeConfirmed) {
            codeConfirmed = true
            updateButton?.message = Component.translatable("screen.updater363.apply_code_confirm")
            return
        }
        updateButton?.active = false
        // The progress page owns the apply future; it forwards to the result screen when it finishes.
        val destination = UpdateScreenNavigation.resultParent(parentScreen)
        ClientScreens.set(
            UpdateProgressScreen(destination, UpdaterService.apply(currentPlan)) { result ->
                ClientScreens.set(
                    if (result.requiresRestart) RestartChoiceScreen(destination)
                    else UpdateResultScreen(destination, result.reloadFailures),
                )
            },
        )
    }

    override fun onClose() {
        ClientScreens.set(parentScreen)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && scrollbar.visible) {
            val top = 45
            val trackHeight = height - 90
            val trackX = width - 20
            if (mouseX.toInt() in trackX until trackX + 6 && mouseY.toInt() in top until top + trackHeight) {
                scrollbar.clickTrack(top, trackHeight, mouseY.toInt())
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) scrollbar.release()
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (button == 0 && scrollbar.dragTo(45, height - 90, mouseY.toInt())) return true
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        val heading = title.string
        graphics.drawString(font, heading, (width - font.width(heading)) / 2, 20, 0xFFFFFFFF.toInt())
        when {
            loading -> renderPreviewProgress(graphics)
            error != null -> renderMessage(
                graphics,
                Component.translatable(
                    "screen.updater363.error",
                    error ?: Component.translatable("screen.updater363.unknown_error").string,
                ).string,
                0xFFFF5555.toInt(),
            )
            plan == null -> renderMessage(graphics, Component.translatable("screen.updater363.no_diff").string, 0xFFCCCCCC.toInt())
            else -> renderDetails(graphics)
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        scrollbar.update(detailLines.size * 12, height - 90)
        if (mouseY.toInt() in 45 until height - 45 && detailLines.isNotEmpty()) {
            scrollbar.scrollBy(-(scrollY * 30).toInt())
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }


    /** Preview runs on the shared tracker, so reuse the progress page layout while downloading. */
    private fun renderPreviewProgress(graphics: GuiGraphics) {
        val model = ProgressViewModel.of(UpdateProgressTracker.progress)
        ProgressLayout.draw(object : ProgressCanvas {
            override val canvasWidth: Int get() = width
            override val canvasHeight: Int get() = height
            override fun textWidth(value: String): Int = font.width(value)
            override fun drawText(value: String, x: Int, y: Int, color: Int) {
                graphics.drawString(font, value, x, y, color)
            }
            override fun fill(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
                graphics.fill(x1, y1, x2, y2, color)
            }
        }, model, { key, arg ->
            if (arg is String && arg.isEmpty()) Component.translatable(key).string
            else Component.translatable(key, arg).string
        }, Component.translatable("screen.updater363.progress.title").string)
    }

    private fun renderMessage(graphics: GuiGraphics, message: String, color: Int) {
        graphics.drawString(font, fit(message, width - 40), 20, 60, color)
    }

    private fun renderDetails(graphics: GuiGraphics) {
        val top = 45
        val bottom = height - 45
        val trackX = width - 20
        scrollbar.update(detailLines.size * 12, bottom - top)
        val contentRight = if (scrollbar.visible) trackX - 4 else width - 14
        graphics.enableScissor(14, top, contentRight, bottom)
        var y = top - scrollbar.offset
        detailLines.forEach { line ->
            if (y + 12 >= top && y < bottom) graphics.drawString(font, fit(line.text, contentRight - 20), 20, y, line.color)
            y += 12
        }
        graphics.disableScissor()
        if (scrollbar.visible) {
            graphics.fill(trackX, top, trackX + 6, bottom, 0x55444444)
            val thumbTop = scrollbar.thumbTop(top, bottom - top)
            graphics.fill(trackX, thumbTop, trackX + 6, thumbTop + scrollbar.thumbHeight(bottom - top), 0xFFAAAAAA.toInt())
        }
    }

    private fun buildLines(plan: UpdatePlan): List<RenderLine> = buildList {
        add(RenderLine("${plan.currentVersion} -> ${plan.targetVersion}", 0xFFFFFFFF.toInt()))
        add(RenderLine(Component.translatable(
            "screen.updater363.diff.summary.files",
            plan.changedFiles.size,
            plan.updatedFiles.size,
            plan.deletedFiles.size,
        ).string, 0xFFAAAAAA.toInt()))
        add(RenderLine(Component.translatable(
            "screen.updater363.diff.summary.keys",
            plan.updatedKeys,
            plan.preservedKeys,
            plan.addedKeys,
            plan.removedKeys,
        ).string, 0xFFAAAAAA.toInt()))
        if (plan.loaderUpdateAvailable) add(RenderLine(Component.translatable(
            "screen.updater363.diff.summary.loader",
            plan.currentLoaderVersion,
            plan.targetLoaderVersion,
        ).string, 0xFF55AAFF.toInt()))
        add(RenderLine("", 0xFFFFFFFF.toInt()))
        plan.displayedFiles.forEach { file ->
            val marker = if (file.action == FileAction.DELETE) "-" else "~"
            val color = if (file.action == FileAction.DELETE) 0xFFFF5555.toInt() else 0xFF55AAFF.toInt()
            add(RenderLine("$marker ${file.relativePath}", color))
            file.warning?.let { add(RenderLine("  ! $it", 0xFFFFAA00.toInt())) }
            file.changes.forEach { change ->
                val (keyMarker, keyColor) = when (change.action) {
                    KeyAction.UPDATED -> "~" to 0xFF55AAFF.toInt()
                    KeyAction.PRESERVED -> "=" to 0xFF55FF55.toInt()
                    KeyAction.ADDED -> "+" to 0xFF55FF55.toInt()
                    KeyAction.REMOVED -> "-" to 0xFFFF5555.toInt()
                }
                add(RenderLine("    $keyMarker ${change.key}", keyColor))
            }
        }
    }

    private fun fit(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        val suffix = "..."
        var end = text.length
        while (end > 0 && font.width(text.substring(0, end) + suffix) > maxWidth) end--
        return text.substring(0, end) + suffix
    }
}
