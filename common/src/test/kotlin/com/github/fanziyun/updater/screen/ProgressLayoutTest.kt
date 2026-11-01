package com.github.fanziyun.updater.screen

import com.github.fanziyun.updater.progress.ProgressViewModel
import com.github.fanziyun.updater.progress.UpdatePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressLayoutTest {
    private class FakeCanvas(override val canvasWidth: Int, override val canvasHeight: Int) : ProgressCanvas {
        val texts = mutableListOf<Triple<String, Int, Int>>()
        val rects = mutableListOf<List<Int>>()

        // 1px per character keeps `fit` from truncating captions in assertions.
        override fun textWidth(value: String): Int = value.length
        override fun drawText(value: String, x: Int, y: Int, color: Int) {
            texts += Triple(value, x, y)
        }
        override fun fill(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
            rects += listOf(x1, y1, x2, y2, color)
        }
    }

    private fun model(
        phase: UpdatePhase = UpdatePhase.WRITE,
        fileCaption: String = "1.0 MiB / 2.0 MiB",
        fileFraction: Double = 0.5,
        totalCaption: String = "3 / 8  12.0 MiB / 30.0 MiB",
        totalFraction: Double = 0.375,
    ) = ProgressViewModel(
        phase = phase,
        headlineKey = "screen.updater363.progress.file",
        headlineArg = "mods/sample.jar",
        fileCaption = fileCaption,
        fileFraction = fileFraction,
        filePlaceholderKey = "screen.updater363.progress.unknown",
        totalCaption = totalCaption,
        totalFraction = totalFraction,
        statusKey = "screen.updater363.progress.status",
        statusArg = "",
        failed = phase == UpdatePhase.FAILED,
        finished = phase == UpdatePhase.DONE,
    )

    private fun localize(key: String, arg: Any): String =
        if (arg is String && arg.isNotEmpty()) "$key=$arg" else key

    @Test
    fun `page draws headline file caption and two bars in order`() {
        val canvas = FakeCanvas(320, 240)
        ProgressLayout.draw(canvas, model(), ::localize, "Updating modpack")
        val bounds = ProgressLayout.geometry(320, 240)

        assertTrue(canvas.texts.any { it.first == "Updating modpack" && it.third == bounds.titleY })
        assertTrue(canvas.texts.any { it.first == "screen.updater363.progress.file=mods/sample.jar" })
        assertTrue(canvas.texts.any { it.first.endsWith("1.0 MiB / 2.0 MiB") && it.third == bounds.fileCaptionY })
        assertTrue(canvas.texts.any { it.first.endsWith("3 / 8  12.0 MiB / 30.0 MiB") && it.third == bounds.totalCaptionY })

        // Two bars: each contributes a border rect, a track rect and a fill rect (fraction > 0).
        assertEquals(6, canvas.rects.size)
        val fileFill = canvas.rects[2]
        assertEquals(bounds.barLeft, fileFill[0])
        assertEquals(bounds.barLeft + (0.5 * ProgressLayout.BAR_WIDTH).toInt(), fileFill[2])
    }

    @Test
    fun `bars are ordered above their captions and never overlap`() {
        val bounds = ProgressLayout.geometry(320, 240)
        assertTrue(bounds.headlineY < bounds.fileCaptionY)
        assertTrue(bounds.fileCaptionY < bounds.fileBarY)
        assertTrue(bounds.fileBarY + ProgressLayout.BAR_HEIGHT <= bounds.totalCaptionY)
        assertTrue(bounds.totalCaptionY < bounds.totalBarY)
        assertTrue(bounds.totalBarY + ProgressLayout.BAR_HEIGHT <= bounds.statusY)
    }

    @Test
    fun `unknown totals fall back to placeholder captions`() {
        val canvas = FakeCanvas(320, 240)
        ProgressLayout.draw(canvas, model(fileCaption = "", totalCaption = "", fileFraction = 0.0), ::localize, "t")
        assertTrue(canvas.texts.any { it.first.contains("screen.updater363.progress.unknown") })
    }
}
