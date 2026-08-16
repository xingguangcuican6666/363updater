package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.merge.ManagedFileRecord
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun `round trips managed files optional selections pending work and launch metrics`() {
        val path = Files.createTempDirectory("363updater-state").resolve("state.json")
        val key = VersionTracker.key("pack", "26.1.2", "fabric")
        val managed = ManagedFileRecord(
            relativePath = "mods/optional.jar",
            size = 123L,
            hashes = FileHashes(sha1 = "a".repeat(40), sha512 = "b".repeat(128)),
            optional = true,
            sourceHost = "cdn.example.test",
        )
        val expected = InstallationState(
            version = TrackedVersion("2.0.0", "version-id"),
            managedFiles = mapOf(managed.relativePath to managed),
            optionalSelections = setOf(managed.relativePath),
            pendingTransaction = "12345678-1234-1234-1234-123456789abc",
            metrics = LaunchMetrics(12_345L, 987_654L),
        )

        VersionTracker.writeInstallation(path, key, expected)

        assertEquals(expected, VersionTracker.readInstallation(path, key))
        assertTrue(Files.readString(path).contains("\"schemaVersion\": 2"))
    }

    @Test
    fun `treats nullable optional state fields as absent`() {
        val path = Files.createTempFile("363updater-state", ".json")
        val key = VersionTracker.key("pack", "26.1.2", "fabric")
        Files.writeString(
            path,
            """
                {
                  "schemaVersion": 2,
                  "versions": {
                    "$key": {
                      "number": "1.0.0",
                      "id": null,
                      "managedFiles": [],
                      "optionalSelections": null,
                      "pendingTransaction": null,
                      "lastSuccessfulStartupMillis": null,
                      "peakCombinedRssBytes": null
                    }
                  }
                }
            """.trimIndent(),
        )

        val state = VersionTracker.readInstallation(path, key)!!

        assertNull(state.version.id)
        assertNull(state.pendingTransaction)
        assertEquals(LaunchMetrics(), state.metrics)
        assertTrue(state.managedFiles.isEmpty())
    }
}
