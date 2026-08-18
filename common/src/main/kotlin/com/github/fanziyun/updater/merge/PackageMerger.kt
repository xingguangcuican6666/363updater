package com.github.fanziyun.updater.merge

import com.github.fanziyun.updater.config.UpdaterConfig
import com.github.fanziyun.updater.data.ClientEnvironment
import com.github.fanziyun.updater.data.PackageFile
import com.github.fanziyun.updater.data.PackageSnapshot
import com.github.fanziyun.updater.platform.Platform
import com.github.fanziyun.updater.platform.RuntimeEnvironment
import com.github.fanziyun.updater.util.PathSafety
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

object PackageMerger {
    fun preview(
        oldPackage: PackageSnapshot,
        targetPackage: PackageSnapshot,
        config: UpdaterConfig,
        gameDir: Path = Platform.INSTANCE.gameDir,
    ): UpdatePlan = preview(
        oldPackage,
        targetPackage,
        MergeOptions(
            allowTargetDeletes = config.allowTargetDeletes,
            allowUnknownFormatReplacement = config.allowUnknownFormatReplacement,
            updateManagedMods = config.updateManagedMods && !RuntimeEnvironment.isAndroid,
        ),
        gameDir,
    )

    fun preview(
        oldPackage: PackageSnapshot,
        targetPackage: PackageSnapshot,
        options: MergeOptions,
        gameDir: Path = Platform.INSTANCE.gameDir,
    ): UpdatePlan {
        validateCaseCollisions(targetPackage.managedFiles.keys)
        val paths = (oldPackage.managedFiles.keys + targetPackage.managedFiles.keys + oldPackage.files.keys + targetPackage.files.keys)
            .toSortedSet()
        val plans = mutableListOf<FilePlan>()
        val conflicts = mutableListOf<String>()
        val managedManifest = linkedMapOf<String, ManagedFileRecord>()

        paths.forEach { path ->
            val oldEntry = oldPackage.managedFiles[path]
            val targetEntry = targetPackage.managedFiles[path]
            val currentPath = resolvePath(gameDir, path)
            validateCurrentPath(currentPath, path)
            val currentBytes = if (!path.startsWith("mods/") && Files.isRegularFile(currentPath, LinkOption.NOFOLLOW_LINKS)) {
                require(Files.size(currentPath) <= com.github.fanziyun.updater.data.ManagedFileCache.MAX_PREVIEW_FILE_BYTES) {
                    "Local configuration is too large to merge safely: $path"
                }
                Files.readAllBytes(currentPath)
            } else {
                null
            }

            if (path.startsWith("mods/")) {
                val result = planMod(
                    path,
                    oldEntry,
                    targetEntry,
                    currentPath,
                    options,
                )
                if (result.plan != null) {
                    plans += result.plan
                    result.plan.conflict?.let(conflicts::add)
                }
                result.manifest?.let { managedManifest[path] = it }
            } else {
                val targetSelected = targetEntry == null || targetEntry.environment != ClientEnvironment.OPTIONAL ||
                    oldEntry != null && Files.isRegularFile(currentPath, LinkOption.NOFOLLOW_LINKS)
                val plan = planConfig(
                    path,
                    oldPackage.files[path],
                    currentBytes,
                    targetPackage.files[path]?.takeIf { targetSelected },
                    options,
                    targetEntry?.takeIf { targetSelected },
                    forceInstallMissing = targetEntry?.environment == ClientEnvironment.REQUIRED,
                )
                if (plan != null) plans += plan
            }
        }

        val downloadBytes = plans.asSequence()
            .filter { it.action == FileAction.WRITE && it.managedMod && it.bytes == null }
            .sumOf { it.size }
        val restartRequired = plans.any { it.managedMod && it.action != FileAction.UNCHANGED } ||
            plans.any { it.action == FileAction.WRITE && it.relativePath.endsWith(".jar", ignoreCase = true) }
        return UpdatePlan(
            currentVersion = oldPackage.version,
            targetVersion = targetPackage.version,
            files = plans,
            managedFiles = managedManifest,
            conflicts = conflicts.distinct(),
            downloadBytes = downloadBytes,
            requiresRestart = restartRequired,
            codeChanges = restartRequired,
            targetIndexBytes = targetPackage.indexBytes,
        )
    }

    private fun planMod(
        path: String,
        oldEntryFromPackage: PackageFile?,
        targetEntry: PackageFile?,
        currentPath: Path,
        options: MergeOptions,
    ): ModResult {
        val protected = path in options.protectedPaths || path.substringAfterLast('/').contains("363updater", ignoreCase = true)
        val currentExists = Files.isRegularFile(currentPath, LinkOption.NOFOLLOW_LINKS)
        val oldEntry = options.installedManagedFiles[path]?.let { record ->
            PackageFile(
                relativePath = record.relativePath,
                size = record.size,
                hashes = record.hashes,
                environment = if (record.optional) ClientEnvironment.OPTIONAL else ClientEnvironment.REQUIRED,
                embeddedBytes = null,
            )
        } ?: oldEntryFromPackage

        val oldWasSelected = when {
            oldEntry == null -> false
            oldEntry.environment == ClientEnvironment.OPTIONAL ->
                path in options.installedManagedFiles || currentExists && oldEntry.hashes.matches(currentPath)
            else -> true
        }
        val oldMatches = oldEntry != null && currentExists && oldEntry.hashes.matches(currentPath)
        val targetSelected = targetEntry != null &&
            (targetEntry.environment != ClientEnvironment.OPTIONAL || oldMatches)
        val selectedTarget = targetEntry?.takeIf { targetSelected }

        if (!options.updateManagedMods) {
            val manifest = oldEntry?.takeIf { oldWasSelected }?.toManagedRecord()
            return ModResult(null, manifest)
        }

        val currentMatchesTarget = selectedTarget != null && currentExists && selectedTarget.hashes.matches(currentPath)
        if (currentMatchesTarget) return ModResult(null, selectedTarget.toManagedRecord())

        if (oldEntry != null && oldWasSelected && currentExists && !oldEntry.hashes.matches(currentPath)) {
            val conflict = "Managed mod was locally modified: $path"
            return ModResult(
                FilePlan(
                    relativePath = path,
                    action = FileAction.UNCHANGED,
                    warning = conflict,
                    expectedCurrentHashes = oldEntry.hashes,
                    targetHashes = selectedTarget?.hashes,
                    size = selectedTarget?.size ?: oldEntry.size,
                    sourceHost = selectedTarget?.sourceHost,
                    optional = oldEntry.environment == ClientEnvironment.OPTIONAL,
                    managedMod = true,
                    conflict = conflict,
                    protectedFile = protected,
                ),
                oldEntry.toManagedRecord(),
            )
        }

        if (selectedTarget == null) {
            if (oldEntry == null || !oldWasSelected || !currentExists) return ModResult(null, null)
            if (protected) {
                val warning = "Protected updater file kept although the target package removed it"
                return ModResult(
                    FilePlan(
                        relativePath = path,
                        action = FileAction.UNCHANGED,
                        warning = warning,
                        expectedCurrentHashes = oldEntry.hashes,
                        targetHashes = null,
                        size = oldEntry.size,
                        optional = oldEntry.environment == ClientEnvironment.OPTIONAL,
                        managedMod = true,
                        protectedFile = true,
                    ),
                    oldEntry.toManagedRecord(),
                )
            }
            return ModResult(
                FilePlan(
                    relativePath = path,
                    action = FileAction.DELETE,
                    changes = listOf(KeyChange(path, KeyAction.REMOVED)),
                    expectedCurrentHashes = oldEntry.hashes,
                    size = oldEntry.size,
                    optional = oldEntry.environment == ClientEnvironment.OPTIONAL,
                    managedMod = true,
                ),
                null,
            )
        }

        if (oldEntry == null && currentExists) {
            val conflict = "A local file already occupies new managed mod path: $path"
            return ModResult(
                FilePlan(
                    relativePath = path,
                    action = FileAction.UNCHANGED,
                    warning = conflict,
                    expectedCurrentHashes = null,
                    targetHashes = selectedTarget.hashes,
                    size = selectedTarget.size,
                    sourceHost = selectedTarget.sourceHost,
                    optional = selectedTarget.environment == ClientEnvironment.OPTIONAL,
                    managedMod = true,
                    conflict = conflict,
                ),
                null,
            )
        }

        val expected = oldEntry?.takeIf { oldWasSelected && currentExists }?.hashes
        val plan = FilePlan(
            relativePath = path,
            action = FileAction.WRITE,
            bytes = selectedTarget.embeddedBytes,
            changes = listOf(if (oldEntry == null) KeyChange(path, KeyAction.ADDED) else KeyChange(path, KeyAction.UPDATED)),
            expectedCurrentHashes = expected,
            targetHashes = selectedTarget.hashes,
            size = selectedTarget.size,
            sourceHost = selectedTarget.sourceHost,
            optional = selectedTarget.environment == ClientEnvironment.OPTIONAL,
            managedMod = true,
            downloadUrls = selectedTarget.urls,
        )
        return ModResult(plan, selectedTarget.toManagedRecord())
    }

    private fun planConfig(
        path: String,
        old: ByteArray?,
        current: ByteArray?,
        target: ByteArray?,
        options: MergeOptions,
        targetEntry: PackageFile?,
        forceInstallMissing: Boolean = false,
    ): FilePlan? {
        val metadata = FileMetadata(
            expectedCurrent = current,
            targetHashes = targetEntry?.hashes,
            size = targetEntry?.size ?: target?.size?.toLong() ?: 0L,
            sourceHost = targetEntry?.sourceHost,
            optional = targetEntry?.environment == ClientEnvironment.OPTIONAL,
            downloadUrls = targetEntry?.urls.orEmpty(),
        )
        if (target == null) {
            if (old == null || current == null) return null
            if (!options.allowTargetDeletes) {
                return FilePlan(
                    path,
                    FileAction.UNCHANGED,
                    warning = "Target deletion disabled; kept local file",
                    expectedCurrent = current,
                    size = current.size.toLong(),
                )
            }
            if (!current.contentEquals(old)) {
                return FilePlan(
                    path,
                    FileAction.UNCHANGED,
                    warning = "Target removed this file; kept locally modified file",
                    expectedCurrent = current,
                    size = current.size.toLong(),
                )
            }
            return FilePlan(
                path,
                FileAction.DELETE,
                changes = listOf(KeyChange(path, KeyAction.REMOVED)),
                expectedCurrent = current,
                size = current.size.toLong(),
            )
        }
        if (current != null && current.contentEquals(target)) return null
        if (current == null) {
            if (old != null && !forceInstallMissing) {
                return FilePlan(
                    relativePath = path,
                    action = FileAction.UNCHANGED,
                    warning = "Kept local file deletion",
                    expectedCurrent = metadata.expectedCurrent,
                    targetHashes = metadata.targetHashes,
                    downloadUrls = metadata.downloadUrls,
                    size = metadata.size,
                    sourceHost = metadata.sourceHost,
                    optional = metadata.optional,
                )
            }
            return FilePlan(
                relativePath = path,
                action = FileAction.WRITE,
                bytes = target,
                changes = listOf(KeyChange(path, KeyAction.ADDED)),
                expectedCurrent = metadata.expectedCurrent,
                targetHashes = metadata.targetHashes,
                downloadUrls = metadata.downloadUrls,
                size = metadata.size,
                sourceHost = metadata.sourceHost,
                optional = metadata.optional,
            )
        }

        val parsed = runCatching {
            val oldParsed = old?.let { ConfigCodecs.parse(path, it) }
            Triple(oldParsed, ConfigCodecs.parse(path, current), ConfigCodecs.parse(path, target))
        }
        val oldParsed = parsed.getOrNull()?.first
        val currentParsed = parsed.getOrNull()?.second
        val targetParsed = parsed.getOrNull()?.third
        val parsedAsEmptyLineFile = listOfNotNull(oldParsed, currentParsed, targetParsed)
            .all { !ConfigCodecs.hasStructuredValues(it) }
        if (
            currentParsed == null || targetParsed == null || (old != null && oldParsed == null) ||
            parsedAsEmptyLineFile
        ) {
            if (old == null) {
                return FilePlan(
                    path,
                    FileAction.UNCHANGED,
                    warning = "Target also contains this locally created file; kept local contents",
                    expectedCurrent = current,
                    size = current.size.toLong(),
                )
            }
            if (current.contentEquals(old)) {
                return FilePlan(
                    path,
                    FileAction.WRITE,
                    target,
                    warning = "Format is not safely mergeable; replaced unchanged local file",
                    expectedCurrent = current,
                    size = target.size.toLong(),
                )
            }
            if (!options.allowUnknownFormatReplacement) {
                return FilePlan(
                    path,
                    FileAction.UNCHANGED,
                    warning = parsed.exceptionOrNull()?.let { "Parse failed (${it.message}); kept locally modified file" }
                        ?: "Format is not safely mergeable; kept locally modified file",
                    expectedCurrent = current,
                    size = current.size.toLong(),
                )
            }
            return FilePlan(
                path,
                FileAction.WRITE,
                target,
                warning = parsed.exceptionOrNull()?.let { "Parse failed (${it.message}); replaced locally modified file" }
                    ?: "Format is not safely mergeable; replaced locally modified file",
                expectedCurrent = current,
                size = target.size.toLong(),
            )
        }

        val oldTree = oldParsed?.tree ?: JsonObject()
        val merged = requireNotNull(merge(oldTree, currentParsed.tree, targetParsed.tree, options.allowTargetDeletes))
        val bytes = ConfigCodecs.write(currentParsed, merged)
        if (bytes.contentEquals(current)) return null
        val changes = diff(currentParsed.tree, merged, oldTree, targetParsed.tree)
        return FilePlan(
            path,
            FileAction.WRITE,
            bytes,
            changes,
            expectedCurrent = current,
            size = bytes.size.toLong(),
        )
    }

    private data class FileMetadata(
        val expectedCurrent: ByteArray?,
        val targetHashes: com.github.fanziyun.updater.data.FileHashes?,
        val size: Long,
        val sourceHost: String?,
        val optional: Boolean,
        val downloadUrls: List<String>,
    )

    private data class ModResult(val plan: FilePlan?, val manifest: ManagedFileRecord?)

    private fun resolvePath(gameDir: Path, path: String): Path =
        if (path.startsWith("mods/")) PathSafety.resolveManagedPath(gameDir, path) else PathSafety.resolveUnder(gameDir, path)

    private fun validateCurrentPath(path: Path, relative: String) {
        require(
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
        ) { "Updater only supports regular files: $relative" }
    }

    private fun validateCaseCollisions(paths: Set<String>) {
        val collisions = paths.groupBy { it.lowercase() }.filterValues { it.size > 1 }.values.flatten()
        require(collisions.isEmpty()) { "Managed mrpack paths collide case-insensitively: ${collisions.joinToString()}" }
    }

    private fun merge(
        old: JsonElement?,
        current: JsonElement?,
        target: JsonElement?,
        allowTargetDeletes: Boolean,
    ): JsonElement? {
        if (old == null) {
            return when {
                current == null -> target?.copyValue()
                target == null -> current.copyValue()
                current.isJsonObject && target.isJsonObject ->
                    mergeObjects(null, current.asJsonObject, target.asJsonObject, allowTargetDeletes)
                current.sameAs(target) -> current.copyValue()
                else -> current.copyValue()
            }
        }
        if (current == null) return null
        if (target == null) {
            return if (allowTargetDeletes && current.sameAs(old)) null else current.copyValue()
        }
        if (old.isJsonObject && current.isJsonObject && target.isJsonObject) {
            return mergeObjects(old.asJsonObject, current.asJsonObject, target.asJsonObject, allowTargetDeletes)
        }
        return if (current.sameAs(old)) target.copyValue() else current.copyValue()
    }

    private fun mergeObjects(
        old: JsonObject?,
        current: JsonObject,
        target: JsonObject,
        allowTargetDeletes: Boolean,
    ): JsonObject {
        val result = JsonObject()
        val keys = (old?.keySet().orEmpty() + current.keySet() + target.keySet()).toSortedSet()
        keys.forEach { key ->
            merge(old?.get(key), current.get(key), target.get(key), allowTargetDeletes)?.let { result.add(key, it) }
        }
        return result
    }

    private fun diff(current: JsonElement, merged: JsonElement, old: JsonElement, target: JsonElement): List<KeyChange> {
        val keys = (flatten(old).keys + flatten(current).keys + flatten(target).keys + flatten(merged).keys).toSortedSet()
        val currentValues = flatten(current)
        val oldValues = flatten(old)
        val targetValues = flatten(target)
        val mergedValues = flatten(merged)
        return keys.mapNotNull { key ->
            val currentValue = currentValues[key]
            val oldValue = oldValues[key]
            val targetValue = targetValues[key]
            val mergedValue = mergedValues[key]
            when {
                currentValue == null && mergedValue != null -> KeyChange(key, KeyAction.ADDED)
                currentValue != null && mergedValue == null -> KeyChange(key, KeyAction.REMOVED)
                currentValue != null && mergedValue != null && !mergedValue.sameAs(currentValue) -> KeyChange(key, KeyAction.UPDATED)
                currentValue == null && mergedValue == null && oldValue != null && targetValue != null ->
                    KeyChange(key, KeyAction.PRESERVED)
                currentValue != null && mergedValue != null && mergedValue.sameAs(currentValue) &&
                    targetValue?.sameAs(currentValue) != true && (oldValue != null || targetValue != null) ->
                    KeyChange(key, KeyAction.PRESERVED)
                else -> null
            }
        }
    }
}
