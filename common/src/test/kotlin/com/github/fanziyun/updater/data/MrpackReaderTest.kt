package com.github.fanziyun.updater.data

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MrpackReaderTest {
    @Test
    fun `client overrides win and unrelated package files are ignored`() {
        val path = Files.createTempFile("363updater", ".mrpack")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.write("modrinth.index.json", """{"formatVersion":1,"game":"minecraft","files":[],"dependencies":{}}""")
            zip.write("client-overrides/config/sample.json", "client")
            zip.write("overrides/config/sample.json", "shared")
            zip.write("overrides/options.txt", "music:0.5")
            zip.write("overrides/mods/ignored.jar", "ignored")
            zip.write("overrides/config/../outside.txt", "unsafe")
        }

        val snapshot = MrpackReader.read(path, "test")

        assertEquals(setOf("config/sample.json", "options.txt"), snapshot.files.keys)
        assertContentEquals("client".toByteArray(), snapshot.files.getValue("config/sample.json"))
        assertContentEquals("music:0.5".toByteArray(), snapshot.files.getValue("options.txt"))
        assertFalse("outside.txt" in snapshot.files)
    }

    private fun ZipOutputStream.write(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }
}
