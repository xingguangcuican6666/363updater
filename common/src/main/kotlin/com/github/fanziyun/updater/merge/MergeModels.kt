package com.github.fanziyun.updater.merge

enum class FileAction { UNCHANGED, WRITE, DELETE }

enum class KeyAction { UPDATED, PRESERVED, ADDED, REMOVED }

data class MergeOptions(
    val allowTargetDeletes: Boolean = true,
    val allowUnknownFormatReplacement: Boolean = false,
)

data class KeyChange(val key: String, val action: KeyAction)

data class FilePlan(
    val relativePath: String,
    val action: FileAction,
    val bytes: ByteArray? = null,
    val changes: List<KeyChange> = emptyList(),
    val warning: String? = null,
    val expectedCurrent: ByteArray? = null,
) {
    fun changed(): Boolean = action != FileAction.UNCHANGED
}

data class UpdatePlan(
    val currentVersion: String,
    val targetVersion: String,
    val files: List<FilePlan>,
    val project: String = "",
    val minecraftVersion: String = "",
    val loader: String = "",
    val targetVersionId: String = "",
) {
    val changedFiles: List<FilePlan> get() = files.filter(FilePlan::changed)
    val displayedFiles: List<FilePlan> get() = files.filter { it.changed() || it.warning != null }
    val updatedFiles: List<FilePlan> get() = changedFiles.filter { it.action == FileAction.WRITE }
    val deletedFiles: List<FilePlan> get() = changedFiles.filter { it.action == FileAction.DELETE }
    val updatedKeys: Int get() = files.sumOf { file -> file.changes.count { it.action == KeyAction.UPDATED } }
    val preservedKeys: Int get() = files.sumOf { file -> file.changes.count { it.action == KeyAction.PRESERVED } }
    val addedKeys: Int get() = files.sumOf { file -> file.changes.count { it.action == KeyAction.ADDED } }
    val removedKeys: Int get() = files.sumOf { file -> file.changes.count { it.action == KeyAction.REMOVED } }
}
