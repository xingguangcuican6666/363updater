package com.github.fanziyun.updater.merge

import com.github.fanziyun.updater.data.FileHashes
import com.github.fanziyun.updater.data.PackageFile

enum class FileAction { UNCHANGED, WRITE, DELETE }

enum class KeyAction { UPDATED, PRESERVED, ADDED, REMOVED }

data class MergeOptions(
    val allowTargetDeletes: Boolean = true,
    val allowUnknownFormatReplacement: Boolean = false,
    val updateManagedMods: Boolean = false,
    val installedManagedFiles: Map<String, ManagedFileRecord> = emptyMap(),
    val protectedPaths: Set<String> = emptySet(),
)

data class KeyChange(val key: String, val action: KeyAction)

data class FilePlan(
    val relativePath: String,
    val action: FileAction,
    val bytes: ByteArray? = null,
    val changes: List<KeyChange> = emptyList(),
    val warning: String? = null,
    val expectedCurrent: ByteArray? = null,
    val expectedCurrentHashes: FileHashes? = null,
    val targetHashes: FileHashes? = null,
    val downloadUrls: List<String> = emptyList(),
    val size: Long = bytes?.size?.toLong() ?: 0L,
    val sourceHost: String? = null,
    val optional: Boolean = false,
    val managedMod: Boolean = false,
    val conflict: String? = null,
    val protectedFile: Boolean = false,
) {
    fun changed(): Boolean = action != FileAction.UNCHANGED

    fun hasConflict(): Boolean = !conflict.isNullOrBlank()
}

data class ManagedFileRecord(
    val relativePath: String,
    val size: Long,
    val hashes: FileHashes,
    val optional: Boolean = false,
    val sourceHost: String? = null,
)

fun PackageFile.toManagedRecord(): ManagedFileRecord = ManagedFileRecord(
    relativePath = relativePath,
    size = size,
    hashes = hashes,
    optional = environment.name == "OPTIONAL",
    sourceHost = sourceHost,
)

data class UpdatePlan(
    val currentVersion: String,
    val targetVersion: String,
    val files: List<FilePlan>,
    val project: String = "",
    val minecraftVersion: String = "",
    val loader: String = "",
    val targetVersionId: String = "",
    val managedFiles: Map<String, ManagedFileRecord> = emptyMap(),
    val conflicts: List<String> = emptyList(),
    val downloadBytes: Long = 0L,
    val requiresRestart: Boolean = false,
    val codeChanges: Boolean = false,
    val targetIndexBytes: ByteArray? = null,
) {
    val changedFiles: List<FilePlan> get() = files.filter(FilePlan::changed)
    val displayedFiles: List<FilePlan> get() = files.filter { it.changed() || it.warning != null }
    val updatedFiles: List<FilePlan> get() = changedFiles.filter { it.action == FileAction.WRITE }
    val deletedFiles: List<FilePlan> get() = changedFiles.filter { it.action == FileAction.DELETE }
    val updatedKeys: Int get() = files.sumOf { file -> file.changes.count { it.action == KeyAction.UPDATED } }
    val preservedKeys: Int get() = files.sumOf { file -> file.changes.count { it.action == KeyAction.PRESERVED } }
    val addedKeys: Int get() = files.sumOf { file -> file.changes.count { it.action == KeyAction.ADDED } }
    val removedKeys: Int get() = files.sumOf { file -> file.changes.count { it.action == KeyAction.REMOVED } }
    val modFiles: List<FilePlan> get() = files.filter { it.managedMod }
    val configFiles: List<FilePlan> get() = files.filterNot { it.managedMod }
    val protectedFiles: List<FilePlan> get() = files.filter { it.protectedFile }
    val hasConflicts: Boolean get() = conflicts.isNotEmpty() || files.any(FilePlan::hasConflict)
}
