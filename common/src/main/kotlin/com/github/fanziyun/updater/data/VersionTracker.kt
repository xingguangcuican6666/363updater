package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.config.UpdaterConfig
import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.platform.Platform
import com.github.fanziyun.updater.util.AtomicFiles
import com.github.fanziyun.updater.util.PathSafety
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale

data class TrackedVersion(val number: String, val id: String? = null)

object VersionTracker {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val statePath
        get() = PathSafety.resolveGamePath("config/updater363-state.json")

    private val updaterConfigPath
        get() = PathSafety.resolveGamePath("config/updater363.json")

    fun read(config: UpdaterConfig, project: String, minecraftVersion: String, loader: String): TrackedVersion? {
        config.currentVersionOverride.trim().takeIf(String::isNotEmpty)?.let { return TrackedVersion(it) }
        if (config.syncChangelog363Version && is363Project(project)) {
            ChangelogBridge.readVersion()?.let { return TrackedVersion(it) }
        }
        return readState(key(project, minecraftVersion, loader))
    }

    fun write(
        config: UpdaterConfig,
        version: String,
        versionId: String?,
        project: String = config.modrinthProject.trim().ifBlank { "363fan" },
        minecraftVersion: String = config.minecraftVersion.trim().ifBlank { Platform.INSTANCE.minecraftVersion },
        loader: String = config.loader.trim().ifBlank { Platform.INSTANCE.loaderId },
    ) {
        if (config.syncChangelog363Version && is363Project(project) && ChangelogBridge.exists()) {
            ChangelogBridge.writeVersion(version)
        }

        val path = statePath
        val root = if (Files.isRegularFile(path)) {
            runCatching {
                JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
            }.onFailure {
                Updater.LOGGER.warn("Unable to read updater version state from {}; replacing it", path, it)
            }.getOrElse { JsonObject() }
        } else JsonObject()
        val versions = root.getAsJsonObject("versions") ?: JsonObject().also { root.add("versions", it) }
        versions.add(key(project, minecraftVersion, loader), JsonObject().apply {
            addProperty("number", version)
            versionId?.takeIf(String::isNotBlank)?.let { addProperty("id", it) }
        })
        AtomicFiles.write(path, (gson.toJson(root) + "\n").toByteArray(StandardCharsets.UTF_8))
        clearBootstrapOverride(config)
    }

    internal fun key(project: String, minecraftVersion: String, loader: String): String =
        listOf(project, minecraftVersion, loader)
            .joinToString("|") { it.trim().lowercase(Locale.ROOT) }

    internal fun is363Project(project: String): Boolean =
        project.trim().equals("363fan", ignoreCase = true) || project.trim().equals("dh89TBlf", ignoreCase = true)

    private fun readState(key: String): TrackedVersion? {
        val path = statePath
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            val value = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                .asJsonObject.getAsJsonObject("versions")?.getAsJsonObject(key) ?: return@runCatching null
            val number = value.get("number")?.asString?.trim().orEmpty()
            if (number.isEmpty()) null else TrackedVersion(number, value.get("id")?.asString?.trim()?.takeIf(String::isNotEmpty))
        }.onFailure { Updater.LOGGER.warn("Unable to read updater version state from {}", path, it) }.getOrNull()
    }

    private fun clearBootstrapOverride(config: UpdaterConfig) {
        if (config.currentVersionOverride.isBlank()) return
        val path = updaterConfigPath
        if (Files.isRegularFile(path)) {
            val root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
            root.addProperty("currentVersionOverride", "")
            AtomicFiles.write(path, (gson.toJson(root) + "\n").toByteArray(StandardCharsets.UTF_8))
        }
        config.currentVersionOverride = ""
    }
}
