package com.github.fanziyun.updater.transaction

import com.github.fanziyun.updater.merge.ManagedFileRecord
import com.github.fanziyun.updater.merge.UpdatePlan
import com.github.fanziyun.updater.util.AtomicFiles
import com.github.fanziyun.updater.util.PathSafety
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Keeps HMCL's Modrinth manifest aligned with a committed updater transaction. */
internal object LauncherMetadata {
    private const val ROOT_INDEX = "modrinth.index.json"
    private const val ROOT_CONFIG = "modpack.cfg"
    private const val BACKUP_INDEX = "backup/launcher/modrinth.index.json"
    private const val BACKUP_CONFIG = "backup/launcher/modpack.cfg"
    private const val STAGED_INDEX = "staged-launcher/modrinth.index.json"
    private const val STAGED_CONFIG = "staged-launcher/modpack.cfg"
    private const val MAX_METADATA_BYTES = 32L * 1024L * 1024L

    private val gson = GsonBuilder().create()

    fun stage(gameDir: Path, transactionPath: Path, plan: UpdatePlan) {
        val targetIndexBytes = plan.targetIndexBytes ?: return
        val currentIndex = gameDir.resolve(ROOT_INDEX)
        val currentConfig = gameDir.resolve(ROOT_CONFIG)
        if (!regularFile(currentIndex) || !regularFile(currentConfig)) return

        val currentIndexBytes = readMetadata(currentIndex)
        val currentConfigBytes = readMetadata(currentConfig)
        val currentIndexJson = parseObject(currentIndexBytes, ROOT_INDEX)
        val currentConfigJson = parseObject(currentConfigBytes, ROOT_CONFIG)
        if (currentConfigJson.get("type")?.asString != "Modrinth") return
        if (currentConfigJson.get("manifest")?.let { it.isJsonObject && it == currentIndexJson } != true) return

        val targetIndexJson = parseObject(targetIndexBytes, ROOT_INDEX)
        require(targetIndexJson.get("formatVersion")?.asInt == 1) { "Unsupported launcher manifest format" }
        val mergedIndex = mergeManagedMods(currentIndexJson, targetIndexJson, plan.managedFiles)
        val stagedIndexBytes = if (mergedIndex == targetIndexJson) {
            targetIndexBytes.copyOf()
        } else {
            gson.toJson(mergedIndex).toByteArray(StandardCharsets.UTF_8)
        }
        val stagedConfig = currentConfigJson.deepCopy().apply {
            add("manifest", mergedIndex.deepCopy())
            targetIndexJson.get("name")?.takeIf { it.isJsonPrimitive }?.let { add("name", it.deepCopy()) }
            targetIndexJson.get("versionId")?.takeIf { it.isJsonPrimitive }?.let { add("version", it.deepCopy()) }
        }

        AtomicFiles.write(PathSafety.resolveStrictRelative(transactionPath, BACKUP_INDEX), currentIndexBytes)
        AtomicFiles.write(PathSafety.resolveStrictRelative(transactionPath, BACKUP_CONFIG), currentConfigBytes)
        AtomicFiles.write(PathSafety.resolveStrictRelative(transactionPath, STAGED_INDEX), stagedIndexBytes)
        AtomicFiles.write(
            PathSafety.resolveStrictRelative(transactionPath, STAGED_CONFIG),
            gson.toJson(stagedConfig).toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun commit(gameDir: Path, transactionPath: Path) {
        val stagedIndex = PathSafety.resolveStrictRelative(transactionPath, STAGED_INDEX)
        val stagedConfig = PathSafety.resolveStrictRelative(transactionPath, STAGED_CONFIG)
        if (!Files.exists(stagedIndex, LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(stagedConfig, LinkOption.NOFOLLOW_LINKS)
        ) return
        require(regularFile(stagedIndex) && regularFile(stagedConfig)) { "Incomplete staged launcher metadata" }

        val currentIndex = gameDir.resolve(ROOT_INDEX)
        val currentConfig = gameDir.resolve(ROOT_CONFIG)
        val backupIndex = PathSafety.resolveStrictRelative(transactionPath, BACKUP_INDEX)
        val backupConfig = PathSafety.resolveStrictRelative(transactionPath, BACKUP_CONFIG)
        require(regularFile(currentIndex) && regularFile(currentConfig)) { "Launcher metadata is missing" }
        require(regularFile(backupIndex) && regularFile(backupConfig)) { "Launcher metadata backup is missing" }

        val targetIndex = readMetadata(stagedIndex)
        val targetConfig = readMetadata(stagedConfig)
        val oldIndex = readMetadata(backupIndex)
        val oldConfig = readMetadata(backupConfig)
        val liveIndex = readMetadata(currentIndex)
        val liveConfig = readMetadata(currentConfig)
        require(liveIndex.contentEquals(oldIndex) || liveIndex.contentEquals(targetIndex)) {
            "Launcher manifest changed after update preparation"
        }
        require(liveConfig.contentEquals(oldConfig) || liveConfig.contentEquals(targetConfig)) {
            "Launcher metadata changed after update preparation"
        }
        if (liveIndex.contentEquals(targetIndex) && liveConfig.contentEquals(targetConfig)) return

        var indexWritten = false
        try {
            if (!liveIndex.contentEquals(targetIndex)) {
                AtomicFiles.write(currentIndex, targetIndex)
                indexWritten = true
            }
            if (!liveConfig.contentEquals(targetConfig)) AtomicFiles.write(currentConfig, targetConfig)
        } catch (exception: Exception) {
            if (indexWritten) runCatching { AtomicFiles.write(currentIndex, oldIndex) }.onFailure(exception::addSuppressed)
            throw exception
        }
    }

    fun restore(gameDir: Path, transactionPath: Path) {
        val backupIndex = PathSafety.resolveStrictRelative(transactionPath, BACKUP_INDEX)
        val backupConfig = PathSafety.resolveStrictRelative(transactionPath, BACKUP_CONFIG)
        if (regularFile(backupIndex)) AtomicFiles.write(gameDir.resolve(ROOT_INDEX), readMetadata(backupIndex))
        if (regularFile(backupConfig)) AtomicFiles.write(gameDir.resolve(ROOT_CONFIG), readMetadata(backupConfig))
    }

    private fun mergeManagedMods(
        current: JsonObject,
        target: JsonObject,
        managedFiles: Map<String, ManagedFileRecord>,
    ): JsonObject {
        val currentFiles = current.getAsJsonArray("files") ?: JsonArray()
        val targetFiles = target.getAsJsonArray("files") ?: JsonArray()
        val mergedFiles = JsonArray()
        currentFiles.filter { pathOf(it)?.startsWith("mods/") != true }.forEach { mergedFiles.add(it.deepCopy()) }

        val added = linkedSetOf<String>()
        targetFiles.forEach { element ->
            val path = pathOf(element) ?: return@forEach
            val record = managedFiles[path] ?: return@forEach
            if (matches(record, element)) {
                mergedFiles.add(element.deepCopy())
                added += path
            }
        }
        currentFiles.forEach { element ->
            val path = pathOf(element) ?: return@forEach
            if (path !in managedFiles || path in added || !matches(managedFiles.getValue(path), element)) return@forEach
            mergedFiles.add(element.deepCopy())
            added += path
        }
        return target.deepCopy().apply { add("files", mergedFiles) }
    }

    private fun pathOf(element: JsonElement): String? = element.asJsonObject
        .get("path")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.replace('\\', '/')
        ?.takeIf { it.startsWith("mods/") }

    private fun matches(record: ManagedFileRecord, element: JsonElement): Boolean {
        val hashes = runCatching {
            com.github.fanziyun.updater.data.FileHashes.from(element.asJsonObject.getAsJsonObject("hashes"))
        }.getOrNull() ?: return false
        return (record.hashes.sha1 == null || record.hashes.sha1.equals(hashes.sha1, ignoreCase = true)) &&
            (record.hashes.sha512 == null || record.hashes.sha512.equals(hashes.sha512, ignoreCase = true))
    }

    private fun parseObject(bytes: ByteArray, name: String): JsonObject = runCatching {
        JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).asJsonObject
    }.getOrElse { throw IllegalArgumentException("Invalid $name", it) }

    private fun readMetadata(path: Path): ByteArray {
        require(Files.size(path) <= MAX_METADATA_BYTES) { "Launcher metadata is too large: $path" }
        return Files.readAllBytes(path)
    }

    private fun regularFile(path: Path): Boolean =
        !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
}
