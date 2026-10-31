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
        val libraries = FabricLoaderUpdater.librariesFor(metaPayload)
        val names = libraries.map { it.name }
        assertTrue(names.contains("net.fabricmc:fabric-loader:0.19.5"))
        assertTrue(names.contains("net.fabricmc:intermediary:0.0.0"))
        assertTrue(names.contains("org.ow2.asm:asm:9.10.1"))
        assertTrue(names.contains("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7"))
        assertFalse(names.any { it.contains("mixinextras") }, "development-only libs must not ship to a client profile")
    }

    @Test
    fun `library relativePath and downloadUrl follow maven layout`() {
        val library = FabricLoaderUpdater.librariesFor(metaPayload).first { it.artifact == "net.fabricmc:fabric-loader" }
        assertEquals("net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar", library.relativePath)
        // loader/intermediary have no meta url, so downloadUrl is null and the caller falls back.
        assertNull(library.downloadUrl())
        val asm = FabricLoaderUpdater.librariesFor(metaPayload).first { it.artifact == "org.ow2.asm:asm" }
        assertEquals("https://maven.fabricmc.net/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar", asm.downloadUrl())
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
    fun `profileLoaderVersion reads the loader the launcher will actually resolve`() {
        val profile = JsonParser.parseString(
            """{"id":"x","libraries":[{"name":"net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7"},{"name":"net.fabricmc:fabric-loader:0.19.3"}]}""",
        ).asJsonObject
        assertEquals("0.19.3", FabricLoaderUpdater.profileLoaderVersion(profile))
        val plain = JsonParser.parseString("""{"id":"x","libraries":[{"name":"com.google.code.gson:gson:2.11.0"}]}""").asJsonObject
        assertNull(FabricLoaderUpdater.profileLoaderVersion(plain))
    }

    @Test
    fun `profileLoaderVersion trusts the patches chain over the flattened snapshot`() {
        // HMCL builds the classpath from patches[]; the top-level libraries array is a snapshot it may
        // not read. An earlier patch that moved only the snapshot left them out of step, and the
        // launcher then kept starting the old loader, so the chain has to win.
        val outOfStep = JsonParser.parseString(
            """
            {
              "id": "test update",
              "libraries": [{"name": "net.fabricmc:fabric-loader:0.19.5"}],
              "patches": [
                {"id": "game", "version": "26.1.2", "libraries": [{"name": "com.mojang:brigadier:1.3.10"}]},
                {"id": "fabric", "version": "0.19.3",
                 "libraries": [{"name": "net.fabricmc:fabric-loader:0.19.3"}]}
              ]
            }
            """.trimIndent(),
        ).asJsonObject
        assertEquals("0.19.3", FabricLoaderUpdater.profileLoaderVersion(outOfStep))

        // A profile whose patches own no loader falls back to the snapshot.
        val noLoaderPatch = JsonParser.parseString(
            """
            {
              "id": "x",
              "libraries": [{"name": "net.fabricmc:fabric-loader:0.19.5"}],
              "patches": [{"id": "game", "libraries": [{"name": "com.mojang:brigadier:1.3.10"}]}]
            }
            """.trimIndent(),
        ).asJsonObject
        assertEquals("0.19.5", FabricLoaderUpdater.profileLoaderVersion(noLoaderPatch))
    }
}

class FabricProfilePatcherTest {
    private fun profile(libraries: List<String>, mainClass: String = "net.fabricmc.loader.impl.launch.knot.KnotClient") =
        JsonParser.parseString(
            """{"id":"test update","mainClass":"$mainClass","libraries":[${libraries.joinToString(",") { """{"name":"$it"}""" }}]}""",
        ).asJsonObject

    private val loaderReplacements = listOf(
        FabricProfilePatcher.Library("net.fabricmc:fabric-loader:0.19.5", "https://maven.fabricmc.net/", null),
        FabricProfilePatcher.Library("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7", "https://maven.fabricmc.net/", null),
        FabricProfilePatcher.Library("org.ow2.asm:asm:9.10.1", "https://maven.fabricmc.net/", null),
    )

    @Test
    fun `patchProfile replaces loader and mixin in place and keeps other libs`() {
        val original = profile(listOf("com.google.code.gson:gson:2.11.0", "net.fabricmc:fabric-loader:0.19.3", "net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7"))
        val patched = FabricProfilePatcher.patchProfile(original, loaderReplacements, "net.fabricmc.loader.impl.launch.knot.KnotClient")
        val names = patched.getAsJsonArray("libraries").map { it.asJsonObject.get("name").asString }
        assertEquals(listOf("com.google.code.gson:gson:2.11.0", "net.fabricmc:fabric-loader:0.19.5", "net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7", "org.ow2.asm:asm:9.10.1"), names)
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", patched.get("mainClass").asString)
    }

    @Test
    fun `patchProfile rewrites the HMCL patches chain and follows its version field`() {
        // HMCL builds the classpath from patches[], not the flattened top-level libraries, so patching
        // only the top level leaves the launcher resolving the old loader. Both must move together,
        // including the patch's version field, which HMCL uses to decide whether it is current.
        val original = JsonParser.parseString(
            """
            {
              "id": "test update",
              "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
              "libraries": [
                {"name": "net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7"},
                {"name": "net.fabricmc:fabric-loader:0.19.3"},
                {"name": "com.google.code.gson:gson:2.13.2"}
              ],
              "patches": [
                {"id": "game", "version": "26.1.2", "mainClass": "net.minecraft.client.main.Main",
                 "libraries": [{"name": "com.mojang:brigadier:1.3.10"}]},
                {"id": "fabric", "version": "0.19.3", "priority": 30000,
                 "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                 "libraries": [
                   {"name": "org.ow2.asm:asm:9.10.1"},
                   {"name": "net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7"},
                   {"name": "net.fabricmc:intermediary:0.0.0"},
                   {"name": "net.fabricmc:fabric-loader:0.19.3"}
                 ]}
              ]
            }
            """.trimIndent(),
        ).asJsonObject

        val patched = FabricProfilePatcher.patchProfile(
            original,
            loaderReplacements + FabricProfilePatcher.Library("net.fabricmc:intermediary:0.0.0", null, null),
            "net.fabricmc.loader.impl.launch.knot.KnotClient",
        )

        val patches = patched.getAsJsonArray("patches")
        val game = patches[0].asJsonObject
        val fabric = patches[1].asJsonObject
        assertEquals("game", game.get("id").asString)
        assertEquals("26.1.2", game.get("version").asString, "the game patch must not be touched")
        assertEquals("0.19.5", fabric.get("version").asString, "the loader patch version must follow the loader")
        val fabricNames = fabric.getAsJsonArray("libraries").map { it.asJsonObject.get("name").asString }
        assertEquals(
            listOf(
                "org.ow2.asm:asm:9.10.1",
                "net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7",
                "net.fabricmc:intermediary:0.0.0",
                "net.fabricmc:fabric-loader:0.19.5",
            ),
            fabricNames,
        )
        // The flattened snapshot moves too, so a launcher reading only the top level agrees.
        val topNames = patched.getAsJsonArray("libraries").map { it.asJsonObject.get("name").asString }
        assertEquals("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7", topNames[0])
        assertEquals("net.fabricmc:fabric-loader:0.19.5", topNames[1])
        assertEquals("com.google.code.gson:gson:2.13.2", topNames[2], "unrelated libs keep their position")
    }

    @Test
    fun `resolveProfile accepts a patches-chain-only profile`() {
        val tempDir = Files.createTempDirectory("363-profile")
        try {
            val instance = tempDir.resolve("test update")
            Files.createDirectories(instance)
            Files.writeString(instance.resolve("test update.json"),
                """{"id":"test update","patches":[{"id":"fabric","libraries":[{"name":"net.fabricmc:fabric-loader:0.19.3"}]}]}""")
            assertEquals(instance.resolve("test update.json"), FabricProfilePatcher.resolveProfile(instance, "test update"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `patchProfile appends libraries the profile did not have`() {
        val original = profile(listOf("net.fabricmc:fabric-loader:0.19.3"))
        val patched = FabricProfilePatcher.patchProfile(original, loaderReplacements, null)
        val names = patched.getAsJsonArray("libraries").map { it.asJsonObject.get("name").asString }
        assertEquals(
            listOf("net.fabricmc:fabric-loader:0.19.5", "net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7", "org.ow2.asm:asm:9.10.1"),
            names,
        )
        // mainClass is only rewritten when the meta supplies one and the profile already has it.
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", patched.get("mainClass").asString)
    }

    @Test
    fun `resolveLibrariesRoot walks up to the shared libraries directory`() {
        val tempDir = Files.createTempDirectory("363-profile")
        try {
            val mcRoot = tempDir.resolve(".minecraft")
            Files.createDirectories(mcRoot.resolve("libraries"))
            val instance = mcRoot.resolve("versions").resolve("test update")
            Files.createDirectories(instance)
            assertEquals(mcRoot.resolve("libraries"), FabricProfilePatcher.resolveLibrariesRoot(instance))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolveProfile finds the instance json naming the loader and ignores unrelated json`() {
        val tempDir = Files.createTempDirectory("363-profile")
        try {
            val instance = tempDir.resolve("test update")
            Files.createDirectories(instance)
            Files.writeString(instance.resolve("debug-profile.json"), """{"profile":"default"}""")
            Files.writeString(instance.resolve("test update.json"),
                """{"id":"test update","libraries":[{"name":"net.fabricmc:fabric-loader:0.19.3"}]}""")
            val found = FabricProfilePatcher.resolveProfile(instance, "test update")
            assertEquals(instance.resolve("test update.json"), found)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolveProfile rejects a version id that escapes the instance directory`() {
        val tempDir = Files.createTempDirectory("363-profile")
        try {
            Files.createDirectories(tempDir)
            // A traversal id must not be honoured; only the in-directory profile scan can match.
            val found = FabricProfilePatcher.resolveProfile(tempDir, "../escape")
            assertNull(found)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `writeProfile leaves a preloader backup beside the target`() {
        val tempDir = Files.createTempDirectory("363-profile")
        try {
            val target = tempDir.resolve("test update.json")
            Files.writeString(target, """{"id":"test update","libraries":[]}""")
            val patched = JsonParser.parseString("""{"id":"test update","libraries":[{"name":"net.fabricmc:fabric-loader:0.19.5"}]}""").asJsonObject
            FabricProfilePatcher.writeProfile(target, patched)
            assertEquals("""{"id":"test update","libraries":[{"name":"net.fabricmc:fabric-loader:0.19.5"}]}""", Files.readString(target))
            assertTrue(Files.isRegularFile(tempDir.resolve("test update.json.preloader.bak")))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `libraryPresent matches by sha1 and rejects a mismatch`() {
        val tempDir = Files.createTempDirectory("363-profile")
        try {
            val jar = tempDir.resolve("lib.jar")
            Files.write(jar, "hello".toByteArray())
            // sha1("hello") = aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d
            val matching = FabricProfilePatcher.Library("g:a:1", null, "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d")
            assertTrue(FabricProfilePatcher.libraryPresent(jar, matching))
            val mismatched = FabricProfilePatcher.Library("g:a:1", null, "0000000000000000000000000000000000000000")
            assertFalse(FabricProfilePatcher.libraryPresent(jar, mismatched))
            // No expected sha1 means presence alone is enough.
            val noHash = FabricProfilePatcher.Library("g:a:1", null, null)
            assertTrue(FabricProfilePatcher.libraryPresent(jar, noHash))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}

class LoaderLibraryRewriteTest {
    /**
     * Reproduces the captured HMCL command line: every library jar named absolutely under the shared
     * root, with the loader pinned at the version the game is currently running.
     */
    @Test
    fun `rewrites loader jars a captured command line pins and leaves everything else alone`() {
        val tempDir = Files.createTempDirectory("363-classpath")
        try {
            val root = tempDir.resolve("libraries")
            // The installed jars, laid out the way the profile patch will point at them.
            val loader = root.resolve("net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar")
            val mixin = root.resolve("net/fabricmc/sponge-mixin/0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar")
            listOf(loader, mixin).forEach {
                Files.createDirectories(it.parent)
                Files.write(it, "jar".toByteArray())
            }
            val oldLoader = root.resolve("net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar")
            val oldMixin = root.resolve("net/fabricmc/sponge-mixin/0.17.3+mixin.0.8.7/sponge-mixin-0.17.3+mixin.0.8.7.jar")
            val clientJar = tempDir.resolve("versions/test update/test update.jar")
            listOf(oldLoader, oldMixin, clientJar).forEach {
                Files.createDirectories(it.parent)
                Files.write(it, "old".toByteArray())
            }

            val plan = LoaderLibraryRewrite.plan(
                listOf(
                    oldLoader.toString(),
                    oldMixin.toString(),
                    clientJar.toString(),
                ),
                root,
                listOf(
                    FabricProfilePatcher.Library("net.fabricmc:fabric-loader:0.19.5", null, null),
                    FabricProfilePatcher.Library("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7", null, null),
                ),
            )

            assertEquals(loader.toString(), plan.replacements[oldLoader.toString()])
            assertEquals(mixin.toString(), plan.replacements[oldMixin.toString()])
            assertTrue(plan.unresolved.isEmpty())
            // The game jar is outside the libraries root, so it is never touched.
            assertFalse(plan.replacements.containsKey(clientJar.toString()))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reports a declared loader jar that is missing rather than silently keeping the old one`() {
        val tempDir = Files.createTempDirectory("363-classpath")
        try {
            val root = tempDir.resolve("libraries")
            val oldLoader = root.resolve("net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar")
            Files.createDirectories(oldLoader.parent)
            Files.write(oldLoader, "old".toByteArray())

            val plan = LoaderLibraryRewrite.plan(
                listOf(oldLoader.toString()),
                root,
                listOf(FabricProfilePatcher.Library("net.fabricmc:fabric-loader:0.19.5", null, null)),
            )

            assertTrue(plan.replacements.isEmpty())
            assertEquals(listOf(oldLoader.toString()), plan.unresolved)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an already current classpath needs no rewrite`() {
        val tempDir = Files.createTempDirectory("363-classpath")
        try {
            val root = tempDir.resolve("libraries")
            val loader = root.resolve("net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar")
            Files.createDirectories(loader.parent)
            Files.write(loader, "jar".toByteArray())

            val plan = LoaderLibraryRewrite.plan(
                listOf(loader.toString()),
                root,
                listOf(FabricProfilePatcher.Library("net.fabricmc:fabric-loader:0.19.5", null, null)),
            )

            assertTrue(plan.isEmpty)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `classpathEntries reads every classpath option form`() {
        // A real launcher line puts the main class after the JVM options and the game arguments
        // after that, so `--version` below is never mistaken for an option boundary.
        val separator = java.io.File.pathSeparator
        val main = "net.fabricmc.loader.impl.launch.knot.KnotClient"
        assertEquals(
            listOf("/a.jar", "/b.jar"),
            FabricLoaderUpdater.classpathEntries(listOf("-cp", "/a.jar$separator/b.jar", main, "--version", "test update")),
        )
        assertEquals(
            listOf("/a.jar", "/b.jar"),
            FabricLoaderUpdater.classpathEntries(listOf("-classpath=/a.jar$separator/b.jar", main)),
        )
        assertEquals(emptyList(), FabricLoaderUpdater.classpathEntries(listOf(main, "--gameDir", "/x")))
    }

    @Test
    fun `classpathEntries ignores a game argument that looks like a classpath option`() {
        val separator = java.io.File.pathSeparator
        assertEquals(
            listOf("/a.jar"),
            FabricLoaderUpdater.classpathEntries(
                listOf("-cp", "/a.jar", "Main", "--version", "x", "-cp", "/b.jar$separator/c.jar"),
            ),
        )
    }

    @Test
    fun `a classified library resolves to its classified jar name`() {
        val library = FabricProfilePatcher.Library("com.mojang:jtracy:1.0.37:natives-linux", null, null)
        assertEquals("com/mojang/jtracy/1.0.37/jtracy-1.0.37-natives-linux.jar", library.relativePath)
        assertEquals("com.mojang:jtracy", library.artifact)
    }
}
