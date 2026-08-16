package com.github.fanziyun.updater.screen

import com.github.fanziyun.updater.handoff.ChildHandoffStage
import com.github.fanziyun.updater.handoff.HandoffChildSession
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class HandoffProgressScreen(private val parentScreen: Screen) :
    Screen(Component.translatable("screen.updater363.handoff.title")) {
    private var retryButton: Button? = null
    private var laterButton: Button? = null
    private var doneButton: Button? = null
    private var forceButton: Button? = null
    private var forceConfirmed = false

    override fun init() {
        super.init()
        val buttonWidth = 150
        val gap = 6
        val left = width / 2 - buttonWidth - gap / 2
        val right = width / 2 + gap / 2
        val y = height - 58
        retryButton = addRenderableWidget(Button.builder(Component.translatable("screen.updater363.commit.retry")) {
            HandoffChildSession.retryCommit()
        }.bounds(left, y, buttonWidth, 20).build())
        laterButton = addRenderableWidget(Button.builder(Component.translatable("screen.updater363.commit.later")) {
            HandoffChildSession.deferCommit()
        }.bounds(right, y, buttonWidth, 20).build())
        doneButton = addRenderableWidget(Button.builder(Component.translatable("gui.done")) {
            HandoffChildSession.releaseToTitle()
            ClientScreens.set(parentScreen)
        }.bounds(width / 2 - 100, y, 200, 20).build())
        forceButton = addRenderableWidget(Button.builder(Component.translatable("screen.updater363.old_process.force")) {
            if (forceConfirmed) {
                HandoffChildSession.terminateOldProcess()
                forceButton?.active = false
            } else {
                forceConfirmed = true
                forceButton?.message = Component.translatable("screen.updater363.old_process.confirm")
            }
        }.bounds(width / 2 - 100, y + 24, 200, 20).build())
        updateButtons()
    }

    override fun tick() {
        super.tick()
        updateButtons()
    }

    override fun onClose() {
        if (HandoffChildSession.canLeaveHandoff()) {
            HandoffChildSession.releaseToTitle()
            ClientScreens.set(parentScreen)
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val heading = title.string
        graphics.text(font, heading, (width - font.width(heading)) / 2, 35, 0xFFFFFFFF.toInt())
        val stage = HandoffChildSession.stage
        val message = Component.translatable(stageKey(stage)).string
        graphics.text(font, message, (width - font.width(message)) / 2, 78, stageColor(stage))
        HandoffChildSession.error?.takeIf(String::isNotBlank)?.let { error ->
            graphics.text(font, fit(error, width - 50), 25, 104, 0xFFFF5555.toInt())
        }
        if (HandoffChildSession.oldProcessNeedsForceButton()) {
            val warning = Component.translatable("screen.updater363.old_process.running").string
            graphics.text(font, fit(warning, width - 50), 25, 130, 0xFFFFAA00.toInt())
        }
    }

    private fun updateButtons() {
        val stage = HandoffChildSession.stage
        val failed = stage == ChildHandoffStage.COMMIT_FAILED
        retryButton?.visible = failed
        laterButton?.visible = failed
        doneButton?.visible = HandoffChildSession.canLeaveHandoff()
        forceButton?.visible = HandoffChildSession.oldProcessNeedsForceButton()
    }

    private fun stageKey(stage: ChildHandoffStage): String = when (stage) {
        ChildHandoffStage.CONNECTING -> "screen.updater363.handoff.connecting"
        ChildHandoffStage.STARTING -> "screen.updater363.handoff.starting"
        ChildHandoffStage.FIRST_FRAME, ChildHandoffStage.STABILIZING -> "screen.updater363.handoff.stabilizing"
        ChildHandoffStage.READY -> "screen.updater363.handoff.ready"
        ChildHandoffStage.SHOWING -> "screen.updater363.handoff.showing"
        ChildHandoffStage.WAITING_FOR_OLD_EXIT -> "screen.updater363.handoff.waiting_for_old_exit"
        ChildHandoffStage.COMMITTING -> "screen.updater363.handoff.committing"
        ChildHandoffStage.COMMITTED -> "screen.updater363.handoff.committed"
        ChildHandoffStage.COMMIT_FAILED -> "screen.updater363.handoff.commit_failed"
        ChildHandoffStage.DEFERRED -> "screen.updater363.handoff.deferred"
        ChildHandoffStage.FAILED -> "screen.updater363.handoff.failed"
        ChildHandoffStage.INACTIVE -> "screen.updater363.handoff.starting"
    }

    private fun stageColor(stage: ChildHandoffStage): Int = when (stage) {
        ChildHandoffStage.COMMITTED -> 0xFF55FF55.toInt()
        ChildHandoffStage.COMMIT_FAILED, ChildHandoffStage.FAILED -> 0xFFFF5555.toInt()
        else -> 0xFFCCCCCC.toInt()
    }

    private fun fit(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        val suffix = "..."
        var end = text.length
        while (end > 0 && font.width(text.substring(0, end) + suffix) > maxWidth) end--
        return text.substring(0, end) + suffix
    }
}
