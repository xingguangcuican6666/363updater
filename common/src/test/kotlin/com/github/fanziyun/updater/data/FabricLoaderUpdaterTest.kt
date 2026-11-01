package com.github.fanziyun.updater.data

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FabricLoaderUpdaterTest {
    // A trimmed copy of the real meta.fabricmc.net /versions/loader payload shape:
    // top level has loader / intermediary / launcherMeta, NOT a bare "version".
    private val metaPayload = """
        {
          "loader": {"separator":".","build":5,"maven":"net.fabricmc:fabric-loader:0.19.5","version":"0.19.5","stable":false},
          "intermediary": {"maven":"net.fabricmc:intermediary:0.0.0","version":"0.0.0","stable":true},
          "launcherMeta": {
            "version": 2,
            "mainClass": {"client":"net.fabricmc.loader.impl.launch.knot.KnotClient","server":"x"},
            "libraries": {
              "client": [],
              "common": [
                {"name":"org.ow2.asm:asm:9.10.1","url":"https://maven.fabricmc.net/","sha1":"ada2141c0c"},
                {"name":"net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7","url":"https://maven.fabricmc.net/","sha1":"5f66cc9f59"}
              ],
              "server": [],
              "development": [{"name":"io.github.llamalad7:mixinextras-fabric:0.5.5","url":"https://maven.fabricmc.net/"}]
            },
            "min_java_version": 8
          }
        }
    """.trimIndent()

    @Test
    fun `shouldUpdate only accepts strict upgrades`() {
        assertTrue(FabricLoaderUpdater.shouldUpdate("0.19.3", "0.19.5"))
        assertTrue(FabricLoaderUpdater.shouldUpdate("", "0.19.5"))
        assertFalse(FabricLoaderUpdater.shouldUpdate("0.19.5", "0.19.5"))
        assertFalse(FabricLoaderUpdater.shouldUpdate("0.19.5", "0.19.3"))
        assertFalse(FabricLoaderUpdater.shouldUpdate("0.19.3", ""))
    }

    @Test
    fun `librariesFor returns loader intermediary and common libs but not development`() {
        val names = FabricLoaderUpdater.librariesFor(metaPayload).map { it.name }
        assertTrue(names.contains("net.fabricmc:fabric-loader:0.19.5"))
        assertTrue(names.contains("net.fabricmc:intermediary:0.0.0"))
        assertTrue(names.contains("org.ow2.asm:asm:9.10.1"))
        assertTrue(names.contains("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7"))
        assertFalse(names.any { it.contains("mixinextras") }, "development-only libs must not ship to a client")
    }

    @Test
    fun `library jar path and download url follow maven layout`() {
        val loader = FabricLoaderUpdater.librariesFor(metaPayload).first { it.artifact == "net.fabricmc:fabric-loader" }
        assertEquals("net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar", loader.relativePath)
        // loader/intermediary have no meta url, so downloadUrl is null and the caller falls back.
        assertNull(loader.downloadUrl())
        val asm = FabricLoaderUpdater.librariesFor(metaPayload).first { it.artifact == "org.ow2.asm:asm" }
        assertEquals("https://maven.fabricmc.net/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar", asm.downloadUrl())
    }

    @Test
    fun `a classified library resolves to its classified jar name`() {
        val library = LoaderLibrary("com.mojang:jtracy:1.0.37:natives-linux", null, null)
        assertEquals("com/mojang/jtracy/1.0.37/jtracy-1.0.37-natives-linux.jar", library.relativePath)
        assertEquals("com.mojang:jtracy", library.artifact)
    }

    @Test
    fun `mainClassFor reads launcherMeta mainClass client`() {
        assertEquals(
            "net.fabricmc.loader.impl.launch.knot.KnotClient",
            FabricLoaderUpdater.mainClassFor(metaPayload),
        )
    }

    @Test
    fun `versionIdFromArguments reads launcher version flags`() {
        assertEquals("test update", FabricLoaderUpdater.versionIdFromArguments(listOf("--version", "test update", "--gameDir", "/x")))
        assertEquals("1.20.1", FabricLoaderUpdater.versionIdFromArguments(listOf("--version=1.20.1")))
        assertNull(FabricLoaderUpdater.versionIdFromArguments(listOf("--gameDir", "/x")))
    }

    @Test
    fun `resolveLibrariesRoot prefers the root the captured classpath came from`() {
        val tempDir = Files.createTempDirectory("363-loader-root")
        try {
            // The instance has no libraries/ of its own; the jar it loaded came from the shared root.
            val gameDir = Files.createDirectories(tempDir.resolve(".minecraft/versions/test update"))
            val shared = tempDir.resolve(".minecraft/libraries")
            val jar = Files.createDirectories(shared.resolve("net/fabricmc/fabric-loader/0.19.3"))
                .resolve("fabric-loader-0.19.3.jar")
            Files.writeString(jar, "jar")
            assertEquals(shared, FabricLoaderUpdater.resolveLibrariesRoot(gameDir, listOf(jar.toString())))
            // Without a classpath it falls back to the walk-up.
            assertEquals(shared, FabricLoaderUpdater.resolveLibrariesRoot(gameDir))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `library presence is decided by sha1`() {
        val tempDir = Files.createTempDirectory("363-loader")
        try {
            val jar = tempDir.resolve("lib.jar")
            Files.write(jar, "hello".toByteArray())
            // sha1("hello") = aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d
            val root = Files.createDirectories(tempDir.resolve("libraries"))
            val library = LoaderLibrary("g:a:1", null, "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d")
            val target = library.jarPath(root)
            Files.createDirectories(target.parent)
            Files.write(target, "hello".toByteArray())
            assertTrue(library.presentIn(root))
            assertFalse(library.copy(sha1 = "0".repeat(40)).presentIn(root))
            assertFalse(library.copy(sha1 = null).copy(name = "g:a:2").presentIn(root))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}

class LoaderLibraryPlannerTest {
    private val loader19_5 = LoaderLibrary("net.fabricmc:fabric-loader:0.19.5", null, null)
    private val mixin17_4 = LoaderLibrary("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7", null, null)

    @Test
    fun `findLibrariesRoot walks up to the maven root of a jar`() {
        val root = Path.of("/home/x/.minecraft/libraries")
        val jar = root.resolve("net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar")
        assertEquals(root, LoaderLibraryPlanner.findLibrariesRoot(jar))
        // A path with no maven layout under a directory named libraries is not a match.
        assertNull(LoaderLibraryPlanner.findLibrariesRoot(Path.of("/home/x/libraries/random/game.jar")))
    }

    @Test
    fun `fromJarPath round-trips coordinates including classifiers`() {
        val root = Path.of("/mc/libraries")
        assertEquals(
            "net.fabricmc:fabric-loader:0.19.5",
            LoaderLibrary.fromJarPath(root.resolve("net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar"), root)?.name,
        )
        assertEquals(
            "com.mojang:jtracy:1.0.37:natives-linux",
            LoaderLibrary.fromJarPath(root.resolve("com/mojang/jtracy/1.0.37/jtracy-1.0.37-natives-linux.jar"), root)?.name,
        )
        assertNull(LoaderLibrary.fromJarPath(Path.of("/elsewhere/x.jar"), root))
    }

    @Test
    fun `relaunchClasspath swaps captured loader jars for the installed ones and keeps the rest in order`() {
        val sandbox = Files.createTempDirectory("363-planner")
        try {
            val root = sandbox.resolve("libraries")
            val installed = listOf(loader19_5, mixin17_4).map { library ->
                library.jarPath(root).also { jar ->
                    Files.createDirectories(jar.parent)
                    Files.writeString(jar, "jar")
                }
            }
            val install = FabricLoaderInstall(listOf(loader19_5, mixin17_4), installed, "KnotClient", root)
            val captured = listOf(
                root.resolve("net/fabricmc/sponge-mixin/0.17.3+mixin.0.8.7/sponge-mixin-0.17.3+mixin.0.8.7.jar").toString(),
                root.resolve("net/fabricmc/intermediary/0.0.0/intermediary-0.0.0.jar").toString(),
                root.resolve("net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar").toString(),
                sandbox.resolve("versions/test update/test update.jar").toString(),
                "/opt/extra/user.jar",
            )

            val relaunch = LoaderLibraryPlanner.relaunchClasspath(captured, install)

            // The loader block lands where the first loader jar was, and the non-loader entries keep
            // their relative order on either side of it.
            assertEquals(installed.map { it.toString() }, relaunch.take(2))
            assertEquals(captured[3], relaunch[2], "game jar keeps its position after the loader block")
            assertEquals("/opt/extra/user.jar", relaunch[3])
            assertEquals(4, relaunch.size, "the captured loader jars are dropped, not duplicated")
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun `relaunchClasspath appends the loader jars when the captured classpath had none`() {
        val sandbox = Files.createTempDirectory("363-planner")
        try {
            val root = sandbox.resolve("libraries")
            val installed = listOf(loader19_5).map { library ->
                library.jarPath(root).also { jar ->
                    Files.createDirectories(jar.parent)
                    Files.writeString(jar, "jar")
                }
            }
            val install = FabricLoaderInstall(listOf(loader19_5), installed, null, root)
            assertEquals(
                installed.map { it.toString() },
                LoaderLibraryPlanner.relaunchClasspath(emptyList(), install),
            )
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun `librariesRootFromClasspath picks the root most of the classpath shares`() {
        val a = "/mc/libraries/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar"
        val b = "/mc/libraries/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar"
        val other = "/opt/other/libraries/x/y/1/x-y-1.jar"
        assertEquals(Path.of("/mc/libraries"), LoaderLibraryPlanner.librariesRootFromClasspath(listOf(a, b, other)))
        assertNull(LoaderLibraryPlanner.librariesRootFromClasspath(listOf("/mc/game.jar")))
    }
}