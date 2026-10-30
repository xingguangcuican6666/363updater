package com.github.fanziyun.updater.data

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FabricLoaderUpdaterTest {
    @Test
    fun `shouldUpdate only accepts strict upgrades`() {
        assertTrue(FabricLoaderUpdater.shouldUpdate("0.16.9", "0.16.14"))
        assertTrue(FabricLoaderUpdater.shouldUpdate("", "0.16.14"))
        assertFalse(FabricLoaderUpdater.shouldUpdate("0.16.14", "0.16.14"))
        assertFalse(FabricLoaderUpdater.shouldUpdate("0.16.14", "0.16.9"))
        assertFalse(FabricLoaderUpdater.shouldUpdate("0.16.9", ""))
        assertFalse(FabricLoaderUpdater.shouldUpdate("0.16.9", "   "))
    }

    @Test
    fun `selectInstallerVersion prefers the newest stable entry`() {
        val payload = """
            [
              {"version":"1.0.1","stable":true,"url":""},
              {"version":"1.1.0-beta","stable":false,"url":""},
              {"version":"1.0.0","stable":true,"url":""}
            ]
        """.trimIndent()
        assertEquals("1.0.1", FabricLoaderUpdater.selectInstallerVersion(payload))
    }

    @Test
    fun `installCommand targets the game directory and registers the loader profile`() {
        val command = FabricLoaderUpdater.installCommand(
            java = Path.of("/jvm/bin/java"),
            installerJar = Path.of("/cache/fabric-installer-1.0.1.jar"),
            gameDir = Path.of("/game"),
            minecraftVersion = "26.1.2",
            loaderVersion = "0.18.6",
        )
        assertEquals("/jvm/bin/java", command[0])
        assertEquals("-jar", command[1])
        assertEquals("/cache/fabric-installer-1.0.1.jar", command[2])
        assertEquals(listOf("client", "-dir", "/game", "-mcversion", "26.1.2", "-loader", "0.18.6"),
            command.subList(3, command.size))
    }

    @Test
    fun `meta urls are well formed and encoded`() {
        assertEquals(
            "https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.16.14",
            FabricLoaderUpdater.loaderMetaUrl("1.21.1", "0.16.14"),
        )
        assertEquals(
            "https://meta.fabricmc.net/v2/versions/installer",
            FabricLoaderUpdater.installerListUrl(),
        )
        assertEquals(
            "https://meta.fabricmc.net/v2/versions/installer/1.0.1/installer/jar",
            FabricLoaderUpdater.installerJarUrl("1.0.1"),
        )
    }
}
