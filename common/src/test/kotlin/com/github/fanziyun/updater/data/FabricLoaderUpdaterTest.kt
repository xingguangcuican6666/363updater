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
    fun `profileLoaderVersion reads the pinned fabric-loader library`() {
        val profile = JsonParser.parseString(
            """{"id":"x","libraries":[{"name":"net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7"},{"name":"net.fabricmc:fabric-loader:0.19.3"}]}""",
        ).asJsonObject
        assertEquals("0.19.3", FabricLoaderUpdater.profileLoaderVersion(profile))
        val plain = JsonParser.parseString("""{"id":"x","libraries":[{"name":"com.google.code.gson:gson:2.11.0"}]}""").asJsonObject
        assertNull(FabricLoaderUpdater.profileLoaderVersion(plain))
    }
}

class FabricProfilePatcherTest {
    private fun profile(libraries: List<String>, mainClass: String = "net.fabricmc.loader.impl.launch.knot.KnotClient") =
        JsonParser.parseString(
            """{"id":"test update","mainClass":"$mainClass","libraries":[${libraries.joinToString(",") { """{"name":"$it"}""" }}]}""",
        ).asJsonObject

    @Test
    fun `patchProfile replaces loader and mixin in place and keeps other libs`() {
        val original = profile(listOf("com.google.code.gson:gson:2.11.0", "net.fabricmc:fabric-loader:0.19.3", "net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7"))
        val replacements = listOf(
            FabricProfilePatcher.Library("net.fabricmc:fabric-loader:0.19.5", "https://maven.fabricmc.net/", null),
            FabricProfilePatcher.Library("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7", "https://maven.fabricmc.net/", null),
        )
        val patched = FabricProfilePatcher.patchProfile(original, replacements, "net.fabricmc.loader.impl.launch.knot.KnotClient")
        val names = patched.getAsJsonArray("libraries").map { it.asJsonObject.get("name").asString }
        assertEquals(listOf("com.google.code.gson:gson:2.11.0", "net.fabricmc:fabric-loader:0.19.5", "net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7"), names)
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", patched.get("mainClass").asString)
    }

    @Test
    fun `patchProfile appends libraries the profile did not have`() {
        val original = profile(listOf("net.fabricmc:fabric-loader:0.19.3"))
        val replacements = listOf(
            FabricProfilePatcher.Library("net.fabricmc:fabric-loader:0.19.5", null, null),
            FabricProfilePatcher.Library("org.ow2.asm:asm:9.10.1", "https://maven.fabricmc.net/", null),
        )
        val patched = FabricProfilePatcher.patchProfile(original, replacements, null)
        val names = patched.getAsJsonArray("libraries").map { it.asJsonObject.get("name").asString }
        assertEquals(listOf("net.fabricmc:fabric-loader:0.19.5", "org.ow2.asm:asm:9.10.1"), names)
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
