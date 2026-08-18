package com.github.fanziyun.updater.screen

/** Geometry and input state for the update-diff scrollbar. */
class DiffScrollbarState(private val minimumThumbHeight: Int = 16) {
    var offset: Int = 0
        private set
    private var contentHeight: Int = 0
    private var viewportHeight: Int = 0
    private var dragOffset: Int = 0
    private var dragging = false

    val maxOffset: Int
        get() = (contentHeight - viewportHeight).coerceAtLeast(0)

    val visible: Boolean
        get() = maxOffset > 0

    fun update(contentHeight: Int, viewportHeight: Int) {
        this.contentHeight = contentHeight.coerceAtLeast(0)
        this.viewportHeight = viewportHeight.coerceAtLeast(0)
        offset = offset.coerceIn(0, maxOffset)
        if (!visible) {
            offset = 0
            dragging = false
        }
    }

    fun scrollBy(amount: Int) {
        offset = (offset + amount).coerceIn(0, maxOffset)
    }

    fun thumbHeight(trackHeight: Int): Int {
        if (!visible || trackHeight <= 0) return 0
        return ((trackHeight.toLong() * viewportHeight / contentHeight).toInt())
            .coerceIn(minimumThumbHeight.coerceAtMost(trackHeight), trackHeight)
    }

    fun thumbTop(trackTop: Int, trackHeight: Int): Int {
        val travel = trackHeight - thumbHeight(trackHeight)
        if (!visible || travel <= 0) return trackTop
        return trackTop + (offset.toLong() * travel / maxOffset).toInt()
    }

    fun clickTrack(trackTop: Int, trackHeight: Int, mouseY: Int): Boolean {
        if (!visible || trackHeight <= 0) return false
        val height = thumbHeight(trackHeight)
        val top = (mouseY - height / 2).coerceIn(trackTop, trackTop + trackHeight - height)
        setOffsetForThumbTop(trackTop, trackHeight, top)
        dragOffset = mouseY - thumbTop(trackTop, trackHeight)
        dragging = true
        return true
    }

    fun dragTo(trackTop: Int, trackHeight: Int, mouseY: Int): Boolean {
        if (!dragging || !visible) return false
        val height = thumbHeight(trackHeight)
        val top = (mouseY - dragOffset).coerceIn(trackTop, trackTop + trackHeight - height)
        setOffsetForThumbTop(trackTop, trackHeight, top)
        return true
    }

    fun release() {
        dragging = false
    }

    private fun setOffsetForThumbTop(trackTop: Int, trackHeight: Int, thumbTop: Int) {
        val travel = trackHeight - thumbHeight(trackHeight)
        offset = if (travel <= 0) 0 else {
            ((thumbTop - trackTop).toLong() * maxOffset / travel).toInt().coerceIn(0, maxOffset)
        }
    }
}
