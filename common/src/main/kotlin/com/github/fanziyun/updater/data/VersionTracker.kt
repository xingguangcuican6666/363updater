package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.config.UpdaterConfig
import com.github.fanziyun.updater.merge.ManagedFileRecord
import com.github.fanziyun.updater.platform.Platform
import com.github.fanziyun.updater.util.AtomicFiles
import com.github.fanziyun.updater.util.PathSafety
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

data class TrackedVersion(val number: String, val id: String? = null)

data class LaunchMetrics(
    val lastSuccessfulStartupMillis: Long = 0L,
    val peakCombinedRssBytes: Long = 0L,
)

data class InstallationState(
    val version: TrackedVersion,
    val managedFiles: Map<String, ManagedFileRecord> = emptyMap(),
    val optionalSelections: Set<String> = emptySet(),
    val pendingTransaction: String? = null,
    val metrics: LaunchMetrics = LaunchMetrics(),
)

object VersionTracker {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun statePath(gameDir: Path): Path =
        PathSafety.resolveUnder(gameDir, "config/updater363-state.json")

    private fun updaterConfigPath(gameDir: Path): Path =
        PathSafety.resolveUnder(gameDir, "config/updater363.json")

    fun read(config: UpdaterConfig, project: String, minecraftVersion: String, loader: String): TrackedVersion? {
        config.currentVersionOverride.trim().takeIf(String::isNotEmpty)?.let { return TrackedVersion(it) }
        val tracked = readInstallation(project, minecraftVersion, loader)
        if (config.syncChangelog363Version && is363Project(project)) {
            ChangelogBridge.readVersion()?.let { changelogVersion ->
                return if (tracked?.version?.number == changelogVersion) tracked.version else TrackedVersion(changelogVersion)
            }
        }
        return tracked?.version
    }

    fun readInstallation(
        project: String,
        minecraftVersion: String,
        loader: String,
        gameDir: Path = Platform.INSTANCE.gameDir,
    ): InstallationState? = readInstallation(statePath(gameDir), key(project, minecraftVersion, loader))

    fun write(
        config: UpdaterConfig,
        version: String,
        versionId: String?,
        project: String = config.modrinthProject.trim().ifBlank { "363fan" },
        minecraftVersion: String = config.minecraftVersion.trim().ifBlank { Platform.INSTANCE.minecraftVersion },
        loader: String = config.loader.trim().ifBlank { Platform.INSTANCE.loaderId },
        managedFiles: Map<String, ManagedFileRecord>? = null,
        pendingTransaction: String? = null,
        metrics: LaunchMetrics? = null,
        gameDir: Path = Platform.INSTANCE.gameDir,
    ) {
        val stateKey = key(project, minecraftVersion, loader)
        val path = statePath(gameDir)
        val existing = readInstallation(path, stateKey)
        val next = InstallationState(
            version = TrackedVersion(version, versionId?.takeIf(String::isNotBlank)),
            managedFiles = managedFiles ?: existing?.managedFiles.orEmpty(),
            optionalSelections = (managedFiles ?: existing?.managedFiles.orEmpty())
                .values.filter(ManagedFileRecord::optional).mapTo(linkedSetOf(), ManagedFileRecord::relativePath),
            pendingTransaction = pendingTransaction,
            metrics = metrics ?: existing?.metrics ?: LaunchMetrics(),
        )
        writeInstallation(path, stateKey, next)
        clearBootstrapOverride(config, gameDir)

        if (isPlatformGameDir(gameDir) && config.syncChangelog363Version && is363Project(project) && ChangelogBridge.exists()) {
            runCatching { ChangelogBridge.writeVersion(version) }
                .onFailure { Updater.LOGGER.warn("Unable to synchronize changelog363 version", it) }
        }
    }

    fun markPending(
        project: String,
        minecraftVersion: String,
        loader: String,
        transactionId: String?,
        currentVersion: TrackedVersion? = null,
        gameDir: Path = Platform.INSTANCE.gameDir,
    ) {
        val stateKey = key(project, minecraftVersion, loader)
        val path = statePath(gameDir)
        val current = readInstallation(path, stateKey)
            ?: currentVersion?.let(::InstallationState)
            ?: return
        writeInstallation(path, stateKey, current.copy(pendingTransaction = transactionId))
    }

    internal fun key(project: String, minecraftVersion: String, loader: String): String =
        listOf(project, minecraftVersion, loader)
            .joinToString("|") { it.trim().lowercase(Locale.ROOT) }

    internal fun is363Project(project: String): Boolean =
        project.trim().equals("363fan", ignoreCase = true) || project.trim().equals("dh89TBlf", ignoreCase = true)

    internal fun readInstallation(path: Path, stateKey: String): InstallationState? {
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            val value = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                .asJsonObject.getAsJsonObject("versions")?.getAsJsonObject(stateKey) ?: return@runCatching null
            parseInstallation(value)
        }.onFailure { Updater.LOGGER.warn("Unable to read updater version state from {}", path, it) }.getOrNull()
    }

    internal fun writeInstallation(path: Path, stateKey: String, state: InstallationState) {
        val root = if (Files.isRegularFile(path)) {
            runCatching { JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject }
                .onFailure { Updater.LOGGER.warn("Unable to read updater version state from {}; replacing it", path, it) }
                .getOrElse { JsonObject() }
        } else {
            JsonObject()
        }
        root.addProperty("schemaVersion", 2)
        val versions = root.getAsJsonObject("versions") ?: JsonObject().also { root.add("versions", it) }
        versions.add(stateKey, serializeInstallation(state))
        AtomicFiles.write(path, (gson.toJson(root) + "\n").toByteArray(StandardCharsets.UTF_8))
    }

    private fun parseInstallation(value: JsonObject): InstallationState? {
        val number = value.string("number").orEmpty()
        if (number.isEmpty()) return null
        val files = linkedMapOf<String, ManagedFileRecord>()
        value.array("managedFiles")?.forEach { element ->
            val file = element.asJsonObject
            val relative = file.string("path").orEmpty()
            require(isSafeManagedModPath(relative)) { "Invalid managed file path in updater state" }
            val hashes = FileHashes(
                sha1 = file.string("sha1"),
                sha512 = file.string("sha512"),
            )
            val size = file.long("size") ?: 0L
            require(size >= 0L) { "Invalid managed file size in updater state" }
            files[relative] = ManagedFileRecord(
                relativePath = relative,
                size = size,
                hashes = hashes,
                optional = file.boolean("optional") == true,
                sourceHost = file.string("sourceHost"),
            )
        }
        val optional = value.array("optionalSelections")
            ?.mapNotNullTo(linkedSetOf()) { element ->
                element.takeIf { it.isJsonPrimitive }?.asString?.takeIf(::isSafeManagedModPath)
            }
            ?: files.values.filter(ManagedFileRecord::optional).mapTo(linkedSetOf(), ManagedFileRecord::relativePath)
        return InstallationState(
            version = TrackedVersion(number, value.string("id")),
            managedFiles = files,
            optionalSelections = optional,
            pendingTransaction = value.string("pendingTransaction"),
            metrics = LaunchMetrics(
                lastSuccessfulStartupMillis = value.long("lastSuccessfulStartupMillis") ?: 0L,
                peakCombinedRssBytes = value.long("peakCombinedRssBytes") ?: 0L,
            ),
        )
    }

    private fun serializeInstallation(state: InstallationState): JsonObject = JsonObject().apply {
        addProperty("number", state.version.number)
        state.version.id?.let { addProperty("id", it) }
        add("managedFiles", JsonArray().apply {
            state.managedFiles.toSortedMap().values.forEach { record ->
                add(JsonObject().apply {
                    addProperty("path", record.relativePath)
                    addProperty("size", record.size)
                    record.hashes.sha1?.let { addProperty("sha1", it) }
                    record.hashes.sha512?.let { addProperty("sha512", it) }
                    addProperty("optional", record.optional)
                    record.sourceHost?.let { addProperty("sourceHost", it) }
                })
            }
        })
        add("optionalSelections", JsonArray().apply {
            state.optionalSelections.sorted().forEach(::add)
        })
        state.pendingTransaction?.let { addProperty("pendingTransaction", it) }
        addProperty("lastSuccessfulStartupMillis", state.metrics.lastSuccessfulStartupMillis)
        addProperty("peakCombinedRssBytes", state.metrics.peakCombinedRssBytes)
    }

    private fun JsonObject.string(name: String): String? = get(name)
        ?.takeUnless { it.isJsonNull }
        ?.asString
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun JsonObject.array(name: String): JsonArray? = get(name)
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray

    private fun JsonObject.long(name: String): Long? = get(name)
        ?.takeUnless { it.isJsonNull }
        ?.let { runCatching { it.asLong }.getOrNull() }

    private fun JsonObject.boolean(name: String): Boolean? = get(name)
        ?.takeUnless { it.isJsonNull }
        ?.let { runCatching { it.asBoolean }.getOrNull() }

    private fun isSafeManagedModPath(relative: String): Boolean =
        relative.startsWith("mods/") && relative.indexOf('\\') < 0 &&
            relative.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun isPlatformGameDir(gameDir: Path): Boolean =
        runCatching {
            gameDir.toAbsolutePath().normalize() == Platform.INSTANCE.gameDir.toAbsolutePath().normalize()
        }.getOrDefault(false)

    private fun clearBootstrapOverride(config: UpdaterConfig, gameDir: Path) {
        if (config.currentVersionOverride.isBlank()) return
        val path = updaterConfigPath(gameDir)
        if (Files.isRegularFile(path)) {
            val root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
            root.addProperty("currentVersionOverride", "")
            AtomicFiles.write(path, (gson.toJson(root) + "\n").toByteArray(StandardCharsets.UTF_8))
        }
        config.currentVersionOverride = ""
    }
}
