package com.github.fanziyun.updater.screen

import com.github.fanziyun.updater.progress.ProgressViewModel

/**
 * Minimal drawing surface for the update progress page.
 *
 * Each Minecraft UI source set adapts its own `GuiGraphics`/`GuiGraphicsExtractor` to this interface,
 * which keeps the page layout in one place and lets it be unit-tested without a client.
 */
interface ProgressCanvas {
    val canvasWidth: Int
    val canvasHeight: Int

    fun textWidth(value: String): Int

    fun drawText(value: String, x: Int, y: Int, color: Int)

    fun fill(x1: Int, y1: Int, x2: Int, y2: Int, color: Int)

    /** Truncate [value] with an ellipsis so it fits [maxWidth] pixels. */
    fun fit(value: String, maxWidth: Int): String {
        if (textWidth(value) <= maxWidth) return value
        val suffix = "..."
        var end = value.length
        while (end > 0 && textWidth(value.substring(0, end) + suffix) > maxWidth) end--
        return value.substring(0, end) + suffix
    }

    fun drawCentered(value: String, y: Int, color: Int) {
        val fitted = fit(value, canvasWidth - 40)
        drawText(fitted, (canvasWidth - textWidth(fitted)) / 2, y, color)
    }
}

/**
 * Draws the update progress page.
 *
 * The page follows the requested layout: a headline naming the file being updated, the per-file
 * meter with its `done / total` byte caption, then the overall meter with its item/byte caption.
 * Bars are a track plus a filled portion; a per-file total of zero renders a moving stripe so a
 * stalled transfer is visibly different from a finished one.
 */
object ProgressLayout {
    const val BAR_HEIGHT = 10
    const val BAR_WIDTH = 220
    private const val BAR_BORDER = 0xFF000000.toInt()
    private const val BAR_TRACK = 0xFF2A2A2A.toInt()
    private const val BAR_FILL = 0xFF55AAFF.toInt()
    private const val BAR_FILL_DONE = 0xFF55FF55.toInt()
    private const val BAR_FILL_FAILED = 0xFFFF5555.toInt()
    private const val BAR_STRIPE = 0xFF888888.toInt()
    private const val TEXT_DIM = 0xFFAAAAAA.toInt()
    private const val TEXT_BRIGHT = 0xFFFFFFFF.toInt()
    private const val LINE = 12
    private const val GAP = 6

    data class Geometry(
        val titleY: Int,
        val headlineY: Int,
        val fileCaptionY: Int,
        val fileBarY: Int,
        val totalCaptionY: Int,
        val totalBarY: Int,
        val statusY: Int,
        val barLeft: Int,
        val barWidth: Int,
    )

    fun geometry(width: Int, height: Int): Geometry {
        val top = (height / 2 - 70).coerceAtLeast(40)
        val fileCaption = top + LINE + 14
        val fileBar = fileCaption + LINE + GAP
        val totalCaption = fileBar + BAR_HEIGHT + 18
        val totalBar = totalCaption + LINE + GAP
        return Geometry(
            titleY = 24,
            headlineY = top,
            fileCaptionY = fileCaption,
            fileBarY = fileBar,
            totalCaptionY = totalCaption,
            totalBarY = totalBar,
            statusY = totalBar + BAR_HEIGHT + 18,
            barLeft = (width - BAR_WIDTH) / 2,
            barWidth = BAR_WIDTH,
        )
    }

    /**
     * [localize] resolves a translation key with one optional argument, so the layout never touches
     * `Component` and every screen variant renders the same text.
     */
    fun draw(canvas: ProgressCanvas, model: ProgressViewModel, localize: (String, Any) -> String, title: String) {
        val bounds = geometry(canvas.canvasWidth, canvas.canvasHeight)
        canvas.drawCentered(title, bounds.titleY, TEXT_BRIGHT)
        canvas.drawCentered(
            localize(model.headlineKey, model.headlineArg),
            bounds.headlineY,
            if (model.failed) BAR_FILL_FAILED else if (model.finished) BAR_FILL_DONE else TEXT_BRIGHT,
        )

        val fileCaption = localize(
            "screen.updater363.progress.file_progress",
            model.fileCaption.ifBlank { localize(model.filePlaceholderKey, "") },
        )
        canvas.drawText(canvas.fit(fileCaption, bounds.barWidth), bounds.barLeft, bounds.fileCaptionY, TEXT_DIM)
        drawBar(canvas, bounds.barLeft, bounds.fileBarY, bounds.barWidth, model.fileFraction, model, stripe = true)

        val totalCaption = localize(
            "screen.updater363.progress.total_progress",
            model.totalCaption.ifBlank { localize("screen.updater363.progress.unknown", "") },
        )
        canvas.drawText(canvas.fit(totalCaption, bounds.barWidth), bounds.barLeft, bounds.totalCaptionY, TEXT_DIM)
        drawBar(canvas, bounds.barLeft, bounds.totalBarY, bounds.barWidth, model.totalFraction, model, stripe = false)

        val status = localize(model.statusKey, model.statusArg)
        canvas.drawCentered(status, bounds.statusY, if (model.failed) BAR_FILL_FAILED else TEXT_DIM)
    }

    private fun drawBar(
        canvas: ProgressCanvas,
        left: Int,
        top: Int,
        width: Int,
        fraction: Double,
        model: ProgressViewModel,
        stripe: Boolean,
    ) {
        val right = left + width
        val bottom = top + BAR_HEIGHT
        canvas.fill(left - 1, top - 1, right + 1, bottom + 1, BAR_BORDER)
        canvas.fill(left, top, right, bottom, BAR_TRACK)
        val clamped = fraction.coerceIn(0.0, 1.0)
        val filled = (clamped * width).toInt()
        val color = when {
            model.failed -> BAR_FILL_FAILED
            clamped >= 1.0 -> BAR_FILL_DONE
            else -> BAR_FILL
        }
        when {
            filled > 0 -> canvas.fill(left, top, left + filled, bottom, color)
            stripe && clamped <= 0.0 && !model.finished && !model.failed -> drawStripe(canvas, left, top, right, bottom)
        }
    }

    /** Three moving blocks so an unknown-size transfer still reads as activity. */
    private fun drawStripe(canvas: ProgressCanvas, left: Int, top: Int, right: Int, bottom: Int) {
        val span = right - left
        if (span <= 0) return
        val block = (span / 6).coerceAtLeast(4)
        val shift = ((System.currentTimeMillis() / 24L) % (span + block)).toInt()
        var offset = -block
        while (offset < span) {
            val start = (left + offset + shift).coerceAtLeast(left)
            val end = (left + offset + shift + block).coerceAtMost(right)
            if (end > start) canvas.fill(start, top + 2, end, bottom - 2, BAR_STRIPE)
            offset += block * 3
        }
    }
}
