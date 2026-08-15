package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.util.AtomicFiles
import com.github.fanziyun.updater.util.PathSafety
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object ChangelogBridge {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    val configPath: Path
        get() = PathSafety.resolveGamePath("config/changelog363.json")

    fun exists(): Boolean = Files.isRegularFile(configPath)

    fun readVersion(): String? {
        val path = configPath
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                .asJsonObject.get("modpackVersion")?.asString?.trim()
        }.onFailure { Updater.LOGGER.warn("Unable to read {}", path, it) }.getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    fun writeVersion(version: String) {
        val path = configPath
        val root = if (Files.isRegularFile(path)) {
            JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
        } else {
            JsonObject()
        }
        root.addProperty("modpackVersion", version)
        AtomicFiles.write(path, (gson.toJson(root) + "\n").toByteArray(StandardCharsets.UTF_8))
    }
}
