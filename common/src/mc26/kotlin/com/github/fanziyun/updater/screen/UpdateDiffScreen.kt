package com.github.fanziyun.updater.screen

import com.github.fanziyun.updater.UpdaterService
import com.github.fanziyun.updater.data.ApplyResult
import com.github.fanziyun.updater.merge.FileAction
import com.github.fanziyun.updater.merge.KeyAction
import com.github.fanziyun.updater.merge.UpdatePlan
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
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
                .also { it.active = false }
        )
        addRenderableWidget(
            Button.builder(Component.translatable("gui.back")) { onClose() }
                .bounds(width / 2 + gap / 2, y, buttonWidth, 20)
                .build()
        )
        val future = UpdaterService.preview()
        future.whenComplete { result, exception ->
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
        UpdaterService.apply(currentPlan).whenComplete { result, exception ->
            ClientScreens.execute {
                if (ClientScreens.current() !== this@UpdateDiffScreen) return@execute
                if (exception != null) {
                    error = exception.cause?.message ?: exception.message
                        ?: Component.translatable("screen.updater363.unknown_error").string
                    updateButton?.active = true
                } else {
                    showResult(result)
                }
            }
        }
    }

    private fun showResult(result: ApplyResult) {
        val destination = UpdateScreenNavigation.resultParent(parentScreen)
        ClientScreens.set(
            if (result.requiresRestart) RestartChoiceScreen(destination)
            else UpdateResultScreen(destination, result.reloadFailures),
        )
    }

    override fun onClose() {
        ClientScreens.set(parentScreen)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() == 0 && scrollbar.visible) {
            val top = 45
            val trackHeight = height - 90
            val trackX = width - 20
            if (event.x().toInt() in trackX until trackX + 6 && event.y().toInt() in top until top + trackHeight) {
                scrollbar.clickTrack(top, trackHeight, event.y().toInt())
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (event.button() == 0) scrollbar.release()
        return super.mouseReleased(event)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (event.button() == 0 && scrollbar.dragTo(45, height - 90, event.y().toInt())) return true
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val heading = title.string
        graphics.text(font, heading, (width - font.width(heading)) / 2, 20, 0xFFFFFFFF.toInt())
        when {
            loading -> renderMessage(graphics, Component.translatable("screen.updater363.loading").string, 0xFFCCCCCC.toInt())
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

    private fun renderMessage(graphics: GuiGraphicsExtractor, message: String, color: Int) {
        graphics.text(font, fit(message, width - 40), 20, 60, color)
    }

    private fun renderDetails(graphics: GuiGraphicsExtractor) {
        val top = 45
        val bottom = height - 45
        val trackX = width - 20
        scrollbar.update(detailLines.size * 12, bottom - top)
        val contentRight = if (scrollbar.visible) trackX - 4 else width - 14
        graphics.enableScissor(14, top, contentRight, bottom)
        var y = top - scrollbar.offset
        detailLines.forEach { line ->
            if (y + 12 >= top && y < bottom) graphics.text(font, fit(line.text, contentRight - 20), 20, y, line.color)
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
        if (plan.downloadBytes > 0L) add(RenderLine(
            Component.translatable("screen.updater363.diff.summary.download", humanBytes(plan.downloadBytes)).string,
            0xFFAAAAAA.toInt(),
        ))
        if (plan.codeChanges) add(RenderLine(
            Component.translatable("screen.updater363.code_warning").string,
            0xFFFFAA00.toInt(),
        ))
        if (plan.hasConflicts) add(RenderLine(
            Component.translatable("screen.updater363.conflicts", plan.conflicts.size).string,
            0xFFFF5555.toInt(),
        ))
        add(RenderLine("", 0xFFFFFFFF.toInt()))
        listOf(
            Component.translatable("screen.updater363.section.mods").string to plan.displayedFiles.filter { it.managedMod },
            Component.translatable("screen.updater363.section.options").string to plan.displayedFiles.filter { it.relativePath == "options.txt" },
            Component.translatable("screen.updater363.section.config").string to plan.displayedFiles.filter {
                !it.managedMod && it.relativePath != "options.txt"
            },
        ).forEach { (section, files) ->
            if (files.isEmpty()) return@forEach
            add(RenderLine("[$section]", 0xFFFFFFFF.toInt()))
            files.forEach { file ->
            val marker = when (file.action) {
                FileAction.WRITE -> if (file.expectedCurrent == null && file.expectedCurrentHashes == null) "+" else "~"
                FileAction.DELETE -> "-"
                FileAction.UNCHANGED -> "="
            }
            val color = when {
                file.hasConflict() -> 0xFFFF5555.toInt()
                file.protectedFile -> 0xFFFFAA00.toInt()
                file.action == FileAction.DELETE -> 0xFFFF5555.toInt()
                else -> 0xFF55AAFF.toInt()
            }
            add(RenderLine("$marker ${file.relativePath}", color))
            if (file.managedMod) {
                val hash = (file.targetHashes?.sha512 ?: file.targetHashes?.sha1 ?: file.expectedCurrentHashes?.preferredKey())
                    ?.take(20) ?: "-"
                val details = buildList {
                    add(humanBytes(file.size))
                    add(Component.translatable("screen.updater363.diff.detail.hash", hash).string)
                    file.sourceHost?.let { add(it) }
                    if (file.optional) add(Component.translatable("screen.updater363.diff.detail.optional").string)
                    if (file.protectedFile) add(Component.translatable("screen.updater363.diff.detail.protected").string)
                }.joinToString(" | ")
                add(RenderLine("  $details", 0xFFAAAAAA.toInt()))
            }
            file.conflict?.let { add(RenderLine("  ! $it", 0xFFFF5555.toInt())) }
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
            add(RenderLine("", 0xFFFFFFFF.toInt()))
        }
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.1f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun fit(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        val suffix = "..."
        var end = text.length
        while (end > 0 && font.width(text.substring(0, end) + suffix) > maxWidth) end--
        return text.substring(0, end) + suffix
    }
}
