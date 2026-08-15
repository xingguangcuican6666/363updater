package com.github.fanziyun.updater.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModrinthClientTest {
    @Test
    fun `normalizes configurable api roots`() {
        assertEquals(ModrinthClient.DEFAULT_API_ROOT, ModrinthClient.normalizeApiRoot(""))
        assertEquals("http://127.0.0.1:8763/v2", ModrinthClient.normalizeApiRoot(" http://127.0.0.1:8763/v2/ "))
    }

    @Test
    fun `rejects non-http api roots and roots with query state`() {
        assertFailsWith<IllegalArgumentException> { ModrinthClient.normalizeApiRoot("file:///tmp/modrinth") }
        assertFailsWith<IllegalArgumentException> { ModrinthClient.normalizeApiRoot("http://localhost/v2?mode=test") }
    }
}
