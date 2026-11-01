package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.config.UpdaterConfig
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoaderProfileRewriterTest {
    private val loader19_5 = LoaderLibrary("net.fabricmc:fabric-loader:0.19.5", "https://maven.fabricmc.net/", null)
    private val mixin17_4 = LoaderLibrary("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7", "https://maven.fabricmc.net/", null)
    private val intermediary = LoaderLibrary("net.fabricmc:intermediary:0.0.0", "https://maven.fabricmc.net/", null)

    private fun install(root: Path): FabricLoaderInstall {
        val libraries = listOf(loader19_5, intermediary, mixin17_4)
        val jars = libraries.map { it.jarPath(root) }
        return FabricLoaderInstall(libraries, jars, "net.fabricmc.loader.impl.launch.knot.KnotClient", root)
    }

    /**
     * The exact shape of the real instance profile after a loader update that only touched the
     * relaunch: the flattened array and the patch chain both still name 0.19.3.
     */
    private val upstreamProfile = """
        {
          "id": "test update",
          "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
          "libraries": [
            {"name": "org.ow2.asm:asm:9.10.1"},
            {"name": "net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7"},
            {"name": "net.fabricmc:intermediary:0.0.0"},
            {"name": "net.fabricmc:fabric-loader:0.19.3"},
            {"name": "com.mojang:brigadier:1.3.10"}
          ],
          "patches": [
            {"id": "game", "version": "26.1.2", "libraries": [{"name": "com.mojang:brigadier:1.3.10"}]},
            {"id": "fabric", "version": "0.19.3", "libraries": [
              {"name": "org.ow2.asm:asm:9.10.1"},
              {"name": "net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7"},
              {"name": "net.fabricmc:intermediary:0.0.0"},
              {"name": "net.fabricmc:fabric-loader:0.19.3"}
            ]}
          ]
        }
    """.trimIndent()

    @Test
    fun `rewrite moves every coordinate the install provides, wherever the document keeps it`() {
        val root = Files.createTempDirectory("363-rewrite")
        try {
            val profile = JsonParser.parseString(upstreamProfile).asJsonObject
            val result = LoaderProfileRewriter.rewrite(profile, install(root), "0.19.3")

            assertTrue(result.changed)
            val names = allCoordinates(profile)
            assertTrue(names.contains("net.fabricmc:fabric-loader:0.19.5"), "flattened array and patch chain both move")
            assertTrue(names.contains("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7"))
            assertFalse(names.any { it.endsWith(":0.19.3") }, "no stale loader coordinate remains")
            // The loader block versions follow; the game patch version is untouched.
            val patches = profile.getAsJsonArray("patches")
            assertEquals("0.19.5", patches[1].asJsonObject.get("version").asString)
            assertEquals("26.1.2", patches[0].asJsonObject.get("version").asString)
            assertEquals("0.19.5", LoaderProfileRewriter.loaderVersion(profile))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rewrite is a no-op on a profile that already names the installed loader`() {
        val root = Files.createTempDirectory("363-rewrite")
        try {
            val profile = JsonParser.parseString(
                """{"id":"x","libraries":[{"name":"net.fabricmc:fabric-loader:0.19.5"}]}""",
            ).asJsonObject
            val result = LoaderProfileRewriter.rewrite(profile, install(root), "0.19.3")
            assertFalse(result.changed)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rewrite leaves unrelated coordinates and their sibling keys alone`() {
        val root = Files.createTempDirectory("363-rewrite")
        try {
            val profile = JsonParser.parseString(
                """
                {
                  "libraries": [
                    {"name": "org.apache.logging.log4j:log4j-api:2.25.2", "url": "https://libraries.minecraft.net/"},
                    {"name": "net.fabricmc:fabric-loader:0.19.3", "downloads": {"artifact": {"size": 1}}},
                    {"name": "org.ow2.asm:asm:9.10.1", "url": "https://maven.fabricmc.net/"}
                  ]
                }
                """.trimIndent(),
            ).asJsonObject
            LoaderProfileRewriter.rewrite(profile, install(root), "0.19.3")

            val libraries = profile.getAsJsonArray("libraries")
            val log4j = libraries[0].asJsonObject
            assertEquals("org.apache.logging.log4j:log4j-api:2.25.2", log4j.get("name").asString)
            assertEquals("https://libraries.minecraft.net/", log4j.get("url").asString)
            assertNotNull(libraries[1].asJsonObject.getAsJsonObject("downloads"), "sibling keys survive")
            assertEquals("net.fabricmc:fabric-loader:0.19.5", libraries[1].asJsonObject.get("name").asString)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `findProfile resolves the instance json by version id and ignores unrelated files`() {
        val tempDir = Files.createTempDirectory("363-rewrite")
        try {
            val instance = Files.createDirectories(tempDir.resolve("versions").resolve("test update"))
            Files.writeString(instance.resolve("debug-profile.json"), """{"profile":"default"}""")
            Files.writeString(instance.resolve("test update.json"), upstreamProfile)

            assertEquals(instance.resolve("test update.json"), LoaderProfileRewriter.findProfile(instance, "test update"))
            // The shared layout is also accepted.
            assertEquals(
                instance.resolve("test update.json"),
                LoaderProfileRewriter.findProfile(tempDir, "test update"),
            )
            assertNull(LoaderProfileRewriter.findProfile(instance, "../escape"))
            assertNull(LoaderProfileRewriter.findProfile(tempDir, null))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `write keeps a preloader backup of the previous profile`() {
        val tempDir = Files.createTempDirectory("363-rewrite")
        try {
            val profilePath = tempDir.resolve("test update.json")
            Files.writeString(profilePath, upstreamProfile)
            val profile = JsonParser.parseString(upstreamProfile).asJsonObject
            LoaderProfileRewriter.rewrite(profile, install(tempDir), "0.19.3")
            LoaderProfileRewriter.write(profilePath, profile)

            assertEquals("0.19.5", LoaderProfileRewriter.loaderVersion(JsonParser.parseString(Files.readString(profilePath)).asJsonObject))
            assertEquals(upstreamProfile, Files.readString(tempDir.resolve("test update.json.preloader.bak")))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun allCoordinates(profile: com.google.gson.JsonObject): List<String> {
        val found = mutableListOf<String>()
        fun walk(element: com.google.gson.JsonElement) {
            when (element) {
                is com.google.gson.JsonObject -> {
                    element.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.let(found::add)
                    element.entrySet().forEach { (_, value) -> walk(value) }
                }
                is com.google.gson.JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(profile)
        return found
    }
}