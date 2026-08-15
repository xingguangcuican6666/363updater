package com.github.fanziyun.updater.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionTrackerTest {
    @Test
    fun `normalizes project minecraft and loader into an isolated state key`() {
        assertEquals("363fan|26.1.2|fabric", VersionTracker.key(" 363Fan ", "26.1.2", "Fabric"))
        assertEquals("other|1.21.1|neoforge", VersionTracker.key("other", "1.21.1", "NeoForge"))
    }

    @Test
    fun `only the default project implicitly synchronizes changelog363`() {
        assertTrue(VersionTracker.is363Project("363fan"))
        assertTrue(VersionTracker.is363Project("dh89TBlf"))
        assertTrue(VersionTracker.is363Project("DH89tblF"))
        assertFalse(VersionTracker.is363Project("another-pack"))
    }
}
