package com.github.fanziyun.updater.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiffScrollbarStateTest {
    @Test
    fun `does not display when content fits`() {
        val state = DiffScrollbarState()

        state.update(contentHeight = 100, viewportHeight = 100)

        assertFalse(state.visible)
        assertEquals(0, state.offset)
        assertEquals(0, state.thumbHeight(80))
    }

    @Test
    fun `thumb reaches both ends of the track`() {
        val state = DiffScrollbarState()
        state.update(contentHeight = 400, viewportHeight = 100)

        assertEquals(10, state.thumbTop(trackTop = 10, trackHeight = 80))
        state.scrollBy(300)
        assertEquals(70, state.thumbTop(trackTop = 10, trackHeight = 80))
    }

    @Test
    fun `thumb observes minimum height`() {
        val state = DiffScrollbarState()
        state.update(contentHeight = 10_000, viewportHeight = 100)

        assertEquals(16, state.thumbHeight(80))
    }

    @Test
    fun `track click moves thumb and begins dragging`() {
        val state = DiffScrollbarState()
        state.update(contentHeight = 400, viewportHeight = 100)

        assertTrue(state.clickTrack(trackTop = 10, trackHeight = 80, mouseY = 50))
        assertEquals(150, state.offset)
        assertTrue(state.dragTo(trackTop = 10, trackHeight = 80, mouseY = 80))
        assertEquals(300, state.offset)
    }

    @Test
    fun `resize clamps offset to the new range`() {
        val state = DiffScrollbarState()
        state.update(contentHeight = 400, viewportHeight = 100)
        state.scrollBy(300)

        state.update(contentHeight = 400, viewportHeight = 250)

        assertEquals(150, state.offset)
        state.update(contentHeight = 400, viewportHeight = 400)
        assertEquals(0, state.offset)
        assertFalse(state.visible)
    }
}
