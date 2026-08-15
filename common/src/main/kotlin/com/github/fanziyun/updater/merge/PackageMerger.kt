package com.github.fanziyun.updater.merge

import com.github.fanziyun.updater.config.UpdaterConfig
import com.github.fanziyun.updater.data.PackageSnapshot
import com.github.fanziyun.updater.platform.Platform
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
        MergeOptions(config.allowTargetDeletes, config.allowUnknownFormatReplacement),
        gameDir,
    )

    fun preview(
        oldPackage: PackageSnapshot,
        targetPackage: PackageSnapshot,
        options: MergeOptions,
        gameDir: Path = Platform.INSTANCE.gameDir,
    ): UpdatePlan {
        val paths = (oldPackage.files.keys + targetPackage.files.keys).toSortedSet()
        val plans = paths.mapNotNull { path ->
            val old = oldPackage.files[path]
            val target = targetPackage.files[path]
            val currentPath = PathSafety.resolveUnder(gameDir, path)
            require(
                !Files.exists(currentPath, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isRegularFile(currentPath, LinkOption.NOFOLLOW_LINKS),
            ) { "Updater only supports regular files: $path" }
            val current = currentPath.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                ?.let(Files::readAllBytes)
            planFile(path, old, current, target, options)
        }
        return UpdatePlan(oldPackage.version, targetPackage.version, plans)
    }

    private fun planFile(
        path: String,
        old: ByteArray?,
        current: ByteArray?,
        target: ByteArray?,
        options: MergeOptions,
    ): FilePlan? {
        if (target == null) {
            if (old == null || current == null) return null
            if (!options.allowTargetDeletes) {
                return FilePlan(
                    path,
                    FileAction.UNCHANGED,
                    warning = "Target deletion disabled; kept local file",
                    expectedCurrent = current,
                )
            }
            if (!current.contentEquals(old)) {
                return FilePlan(
                    path,
                    FileAction.UNCHANGED,
                    warning = "Target removed this file; kept locally modified file",
                    expectedCurrent = current,
                )
            }
            return FilePlan(
                path,
                FileAction.DELETE,
                changes = listOf(KeyChange(path, KeyAction.REMOVED)),
                expectedCurrent = current,
            )
        }
        if (current != null && current.contentEquals(target)) return null
        if (current == null) {
            if (old != null) {
                return FilePlan(path, FileAction.UNCHANGED, warning = "Kept local file deletion")
            }
            return FilePlan(
                path,
                FileAction.WRITE,
                target,
                listOf(KeyChange(path, KeyAction.ADDED)),
                expectedCurrent = null,
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
                )
            }
            if (current.contentEquals(old)) {
                return FilePlan(
                    path,
                    FileAction.WRITE,
                    target,
                    warning = "Format is not safely mergeable; replaced unchanged local file",
                    expectedCurrent = current,
                )
            }
            if (!options.allowUnknownFormatReplacement) {
                return FilePlan(
                    path,
                    FileAction.UNCHANGED,
                    warning = parsed.exceptionOrNull()?.let { "Parse failed (${it.message}); kept locally modified file" }
                        ?: "Format is not safely mergeable; kept locally modified file",
                    expectedCurrent = current,
                )
            }
            return FilePlan(
                path,
                FileAction.WRITE,
                target,
                warning = parsed.exceptionOrNull()?.let { "Parse failed (${it.message}); replaced locally modified file" }
                    ?: "Format is not safely mergeable; replaced locally modified file",
                expectedCurrent = current,
            )
        }

        val oldTree = oldParsed?.tree ?: JsonObject()
        val merged = requireNotNull(merge(oldTree, currentParsed.tree, targetParsed.tree, options.allowTargetDeletes))
        val bytes = ConfigCodecs.write(currentParsed, merged)
        if (bytes.contentEquals(current)) return null
        val changes = diff(currentParsed.tree, merged, oldTree, targetParsed.tree)
        return FilePlan(path, FileAction.WRITE, bytes, changes, expectedCurrent = current)
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
        return when {
            current.sameAs(old) -> target.copyValue()
            else -> current.copyValue()
        }
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
