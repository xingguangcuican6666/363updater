package com.github.fanziyun.updater.progress

import com.github.fanziyun.updater.util.ByteFormat

/**
 * Presentation-ready projection of an [UpdateProgress] snapshot.
 *
 * Screens in every UI source set share this so the wording, the two meters and the byte formatting
 * stay identical across Minecraft versions, and so the layout can be unit-tested without a client.
 * The shape mirrors the requested progress page:
 *
 * ```
 * 正在更新：<file>          <- headline (phase text when no single file dominates)
 * 进度：<done> / <total>     <- current-file meter
 * <bar 1>
 * 总进度：<items>            <- overall meter
 * <bar 2>
 * ```
 *
 * Captions are pre-formatted numbers; only [headlineKey] and [statusKey] need translation, and the
 * screens pass [headlineArg]/[statusArg] as their single `%s` parameter.
 */
data class ProgressViewModel(
    val phase: UpdatePhase,
    val headlineKey: String,
    val headlineArg: String,
    val fileCaption: String,
    val fileFraction: Double,
    val totalCaption: String,
    val totalFraction: Double,
    val statusKey: String,
    val statusArg: String,
    val failed: Boolean,
    val finished: Boolean,
) {
    companion object {
        fun of(progress: UpdateProgress): ProgressViewModel {
            val fileCaption = if (progress.hasFile || progress.fileTotalBytes > 0L) {
                ByteFormat.bytes(progress.fileDoneBytes) + " / " + totalText(progress.fileTotalBytes)
            } else {
                ""
            }
            val totalCaption = buildString {
                if (progress.totalItems > 0 || progress.doneItems > 0) {
                    append(ByteFormat.count(progress.doneItems, progress.totalItems))
                }
                if (progress.totalBytes > 0L || progress.doneBytes > 0L) {
                    if (isNotEmpty()) append("  ")
                    append(ByteFormat.bytes(progress.doneBytes))
                    append(" / ")
                    append(totalText(progress.totalBytes))
                }
            }
            val hasFile = progress.fileLabel.isNotBlank()
            return ProgressViewModel(
                phase = progress.phase,
                headlineKey = if (hasFile) "screen.updater363.progress.file" else progress.phase.translationKey(),
                headlineArg = if (hasFile) progress.fileLabel else progress.message,
                fileCaption = fileCaption,
                fileFraction = progress.fileFraction,
                totalCaption = totalCaption,
                totalFraction = progress.totalFraction,
                statusKey = when {
                    progress.phase == UpdatePhase.FAILED -> "screen.updater363.error"
                    progress.message.isNotBlank() -> "screen.updater363.progress.status"
                    else -> progress.phase.translationKey()
                },
                statusArg = progress.message,
                failed = progress.phase == UpdatePhase.FAILED,
                finished = progress.phase == UpdatePhase.DONE,
            )
        }

        private fun totalText(total: Long): String = if (total > 0L) ByteFormat.bytes(total) else "?"
    }
}

/** Translation key for a phase headline, shared by every screen variant. */
fun UpdatePhase.translationKey(): String = when (this) {
    UpdatePhase.IDLE -> "screen.updater363.progress.idle"
    UpdatePhase.DOWNLOAD_PACKAGE -> "screen.updater363.progress.download_package"
    UpdatePhase.ANALYZE -> "screen.updater363.progress.analyze"
    UpdatePhase.PREPARE -> "screen.updater363.progress.prepare"
    UpdatePhase.BACKUP -> "screen.updater363.progress.backup"
    UpdatePhase.WRITE -> "screen.updater363.progress.write"
    UpdatePhase.STAGE -> "screen.updater363.progress.stage"
    UpdatePhase.LOADER -> "screen.updater363.progress.loader"
    UpdatePhase.RELOAD -> "screen.updater363.progress.reload"
    UpdatePhase.DONE -> "screen.updater363.progress.done"
    UpdatePhase.FAILED -> "screen.updater363.progress.failed"
}
