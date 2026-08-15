package com.github.fanziyun.updater.screen

import com.github.fanziyun.updater.UpdaterService
import com.github.fanziyun.updater.merge.FileAction
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
    private var scroll = 0

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
                if (exception != null) error = exception.cause?.message ?: exception.message ?: "Unknown error"
                else {
                    plan = result
                    detailLines = buildLines(result)
                    scroll = 0
                    updateButton?.active = true
                    if (applyAfterLoad) applyUpdate()
                }
            }
        }
    }

    private fun applyUpdate() {
        val currentPlan = plan ?: return
        updateButton?.active = false
        UpdaterService.apply(currentPlan).whenComplete { result, exception ->
            ClientScreens.execute {
                if (ClientScreens.current() !== this@UpdateDiffScreen) return@execute
                if (exception != null) {
                    error = exception.cause?.message ?: exception.message ?: "Update failed"
                    updateButton?.active = true
                } else {
                    ClientScreens.set(
                        UpdateResultScreen(UpdateScreenNavigation.resultParent(parentScreen), result.reloadFailures),
                    )
                }
            }
        }
    }

    override fun onClose() {
        ClientScreens.set(parentScreen)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        val heading = title.string
        graphics.drawString(font, heading, (width - font.width(heading)) / 2, 20, 0xFFFFFFFF.toInt())
        when {
            loading -> renderMessage(graphics, Component.translatable("screen.updater363.loading").string, 0xFFCCCCCC.toInt())
            error != null -> renderMessage(
                graphics,
                Component.translatable("screen.updater363.error", error ?: "Unknown error").string,
                0xFFFF5555.toInt(),
            )
            plan == null -> renderMessage(graphics, Component.translatable("screen.updater363.no_diff").string, 0xFFCCCCCC.toInt())
            else -> renderDetails(graphics)
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollY: Double): Boolean {
        if (mouseY.toInt() in 45 until height - 45 && detailLines.isNotEmpty()) {
            scroll = (scroll - (scrollY * 30).toInt()).coerceIn(0, maxScroll())
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollY)
    }

    private fun renderMessage(graphics: GuiGraphics, message: String, color: Int) {
        graphics.drawString(font, fit(message, width - 40), 20, 60, color)
    }

    private fun renderDetails(graphics: GuiGraphics) {
        val top = 45
        val bottom = height - 45
        graphics.enableScissor(14, top, width - 14, bottom)
        var y = top - scroll
        detailLines.forEach { line ->
            if (y + 12 >= top && y < bottom) graphics.drawString(font, fit(line.text, width - 40), 20, y, line.color)
            y += 12
        }
        graphics.disableScissor()
    }

    private fun buildLines(plan: UpdatePlan): List<RenderLine> = buildList {
        add(RenderLine("${plan.currentVersion} -> ${plan.targetVersion}", 0xFFFFFFFF.toInt()))
        add(RenderLine("files ${plan.changedFiles.size} | write ${plan.updatedFiles.size} | delete ${plan.deletedFiles.size}", 0xFFAAAAAA.toInt()))
        add(RenderLine("keys ~${plan.updatedKeys} =${plan.preservedKeys} +${plan.addedKeys} -${plan.removedKeys}", 0xFFAAAAAA.toInt()))
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

    private fun maxScroll(): Int = (detailLines.size * 12 - (height - 90)).coerceAtLeast(0)

    private fun fit(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        val suffix = "..."
        var end = text.length
        while (end > 0 && font.width(text.substring(0, end) + suffix) > maxWidth) end--
        return text.substring(0, end) + suffix
    }
}
