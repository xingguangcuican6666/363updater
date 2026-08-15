package com.github.fanziyun.updater.data

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionResolverTest {
    @Test
    fun `uses publication order for duplicate or non-monotonic version numbers`() {
        val older = version("old", "0.21.1", "2026-08-12T08:14:01Z")
        val newerSameNumber = version("new", "0.21.1", "2026-08-13T08:14:01Z")
        val newerLowerNumber = version("newer-lower", "0.20.0", "2026-08-14T08:14:01Z")

        assertTrue(VersionResolver.isNewer(newerSameNumber, older))
        assertTrue(VersionResolver.isNewer(newerLowerNumber, older))
        assertFalse(VersionResolver.isNewer(older, newerSameNumber))
        assertFalse(VersionResolver.isNewer(older.copy(), older))
    }

    private fun version(id: String, number: String, published: String) = ModrinthVersion(
        id = id,
        number = number,
        type = "release",
        published = Instant.parse(published),
        environment = "client_only",
        gameVersions = setOf("26.1.2"),
        loaders = setOf("fabric"),
        files = emptyList(),
    )
}
