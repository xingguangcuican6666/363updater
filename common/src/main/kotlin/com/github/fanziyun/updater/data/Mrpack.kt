package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

data class PackageSnapshot(
    val version: String,
    val files: Map<String, ByteArray>,
)

object MrpackReader {
    private const val MAX_FILE_BYTES = 32L * 1024L * 1024L
    private const val MAX_FILES = 8_192

    fun read(path: Path, version: String): PackageSnapshot {
        if (!Files.isRegularFile(path)) error("mrpack does not exist: $path")
        val overrides = linkedMapOf<String, ByteArray>()
        val clientOverrides = linkedMapOf<String, ByteArray>()
        var hasIndex = false
        ZipFile(path.toFile()).use { zip ->
            var count = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (++count > MAX_FILES) error("mrpack contains too many files")
                val name = normalize(entry.name) ?: continue
                if (name == "modrinth.index.json") {
                    val index = zip.getInputStream(entry).use { input -> input.readNBytes((MAX_FILE_BYTES + 1).toInt()) }
                    if (index.size.toLong() > MAX_FILE_BYTES) error("mrpack index is too large")
                    val root = JsonParser.parseString(index.toString(StandardCharsets.UTF_8)).asJsonObject
                    require(root.has("formatVersion")) { "mrpack index has no formatVersion" }
                    hasIndex = true
                    continue
                }
                val destination = when {
                    name == "overrides/options.txt" -> overrides to "options.txt"
                    name.startsWith("overrides/config/") -> overrides to name.removePrefix("overrides/")
                    name == "client-overrides/options.txt" -> clientOverrides to "options.txt"
                    name.startsWith("client-overrides/config/") ->
                        clientOverrides to name.removePrefix("client-overrides/")
                    else -> continue
                }
                val (destinationFiles, relative) = destination
                if (relative == "config/updater363.json" || relative == "config/updater363-state.json") continue
                if (entry.size > MAX_FILE_BYTES) error("mrpack file is too large: $relative")
                val bytes = zip.getInputStream(entry).use { input ->
                    input.readNBytes((MAX_FILE_BYTES + 1).toInt())
                }
                if (bytes.size.toLong() > MAX_FILE_BYTES) error("mrpack file is too large: $relative")
                destinationFiles[relative] = bytes
            }
        }
        require(hasIndex) { "mrpack index is missing" }
        val files = linkedMapOf<String, ByteArray>().apply {
            putAll(overrides)
            putAll(clientOverrides)
        }
        Updater.LOGGER.info("Read mrpack {} with {} update files", version, files.size)
        return PackageSnapshot(version, files)
    }

    private fun normalize(raw: String): String? {
        val name = raw.replace('\\', '/').removePrefix("./")
        if (name.startsWith("/") || name.split('/').any { it == ".." }) return null
        return name
    }
}
