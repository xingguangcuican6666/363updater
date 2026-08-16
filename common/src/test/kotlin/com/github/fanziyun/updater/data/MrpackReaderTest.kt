package com.github.fanziyun.updater.data

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MrpackReaderTest {
    @Test
    fun `client overrides win and managed mod overrides are included`() {
        val path = Files.createTempFile("363updater", ".mrpack")
        val index = """{"formatVersion":1,"game":"minecraft","files":[],"dependencies":{}}"""
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.write("modrinth.index.json", index)
            zip.write("client-overrides/config/sample.json", "client")
            zip.write("overrides/config/sample.json", "shared")
            zip.write("overrides/options.txt", "music:0.5")
            zip.write("overrides/mods/managed.jar", "managed")
            zip.write("overrides/resourcepacks/unmanaged.zip", "ignored")
        }

        val snapshot = MrpackReader.read(path, "test")

        assertEquals(setOf("config/sample.json", "mods/managed.jar", "options.txt"), snapshot.files.keys)
        assertContentEquals("client".toByteArray(), snapshot.files.getValue("config/sample.json"))
        assertContentEquals("managed".toByteArray(), snapshot.files.getValue("mods/managed.jar"))
        assertContentEquals("music:0.5".toByteArray(), snapshot.files.getValue("options.txt"))
        assertContentEquals(index.toByteArray(), snapshot.indexBytes)
        assertFalse("resourcepacks/unmanaged.zip" in snapshot.files)
    }

    @Test
    fun `index applies client environment rules and keeps optional metadata`() {
        val path = Files.createTempFile("363updater", ".mrpack")
        val required = "required".toByteArray()
        val optional = "optional".toByteArray()
        val unsupported = "unsupported".toByteArray()
        val index = """
            {
              "formatVersion": 1,
              "game": "minecraft",
              "dependencies": {},
              "files": [
                ${indexEntry("mods/required.jar", required, "required")},
                ${indexEntry("mods/optional.jar", optional, "optional")},
                ${indexEntry("mods/server-only.jar", unsupported, "unsupported")}
              ]
            }
        """.trimIndent()
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.write("modrinth.index.json", index)
        }

        val snapshot = MrpackReader.read(path, "test")

        assertEquals(setOf("mods/required.jar", "mods/optional.jar"), snapshot.managedFiles.keys)
        assertEquals(ClientEnvironment.REQUIRED, snapshot.managedFiles.getValue("mods/required.jar").environment)
        assertEquals(ClientEnvironment.OPTIONAL, snapshot.managedFiles.getValue("mods/optional.jar").environment)
        assertTrue(snapshot.files.isEmpty())
    }

    @Test
    fun `rejects unsafe archive paths instead of silently ignoring them`() {
        val path = Files.createTempFile("363updater", ".mrpack")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.write("modrinth.index.json", """{"formatVersion":1,"game":"minecraft","files":[],"dependencies":{}}""")
            zip.write("overrides/config/../outside.txt", "unsafe")
        }

        val exception = assertFailsWith<IllegalArgumentException> { MrpackReader.read(path, "test") }

        assertTrue(exception.message.orEmpty().contains("Unsafe mrpack archive path"))
    }

    @Test
    fun `rejects unsafe index paths and duplicate managed paths`() {
        val unsafe = Files.createTempFile("363updater", ".mrpack")
        ZipOutputStream(Files.newOutputStream(unsafe)).use { zip ->
            zip.write(
                "modrinth.index.json",
                """{"formatVersion":1,"game":"minecraft","files":[${indexEntry("mods/../escape.jar", "x".toByteArray())}],"dependencies":{}}""",
            )
        }
        assertFailsWith<IllegalArgumentException> { MrpackReader.read(unsafe, "test") }

        val duplicate = Files.createTempFile("363updater", ".mrpack")
        ZipOutputStream(Files.newOutputStream(duplicate)).use { zip ->
            val entry = indexEntry("mods/duplicate.jar", "x".toByteArray())
            zip.write(
                "modrinth.index.json",
                """{"formatVersion":1,"game":"minecraft","files":[$entry,$entry],"dependencies":{}}""",
            )
        }
        assertFailsWith<IllegalArgumentException> { MrpackReader.read(duplicate, "test") }
    }

    private fun ZipOutputStream.write(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun indexEntry(path: String, bytes: ByteArray, client: String = "required"): String =
        """{"path":"$path","hashes":{"sha1":"${digest(bytes, "SHA-1")}","sha512":"${digest(bytes, "SHA-512")}"},"env":{"client":"$client"},"downloads":["https://example.invalid/$path"],"fileSize":${bytes.size}}"""

    private fun digest(bytes: ByteArray, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }
}
