package com.github.fanziyun.updater.progress

/** Coarse stage of an updater run; screens map this to a localized headline. */
enum class UpdatePhase {
    IDLE,
    DOWNLOAD_PACKAGE,
    ANALYZE,
    PREPARE,
    BACKUP,
    WRITE,
    STAGE,
    LOADER,
    RELOAD,
    DONE,
    FAILED,
}

/**
 * Immutable snapshot of updater work.
 *
 * Two independent meters are carried so the UI can draw the per-file bar and the overall bar
 * without guessing which one a producer meant:
 *  - [fileLabel]/[fileDoneBytes]/[fileTotalBytes] describe the file being processed right now;
 *  - [doneItems]/[totalItems] and [doneBytes]/[totalBytes] describe the whole run.
 *
 * A total of `0` means "unknown"; consumers must not divide by it.
 */
data class UpdateProgress(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val message: String = "",
    val fileLabel: String = "",
    val fileDoneBytes: Long = 0L,
    val fileTotalBytes: Long = 0L,
    val doneItems: Int = 0,
    val totalItems: Int = 0,
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
) {
    /** Fraction of the current file, `0.0` when its size is unknown, `1.0` when nothing is pending. */
    val fileFraction: Double
        get() = fraction(fileDoneBytes, fileTotalBytes)

    /**
     * Fraction of the whole run. Item counts win because they stay meaningful while a single large
     * download dominates the byte total; bytes are the fallback for runs without a known item count.
     */
    val totalFraction: Double
        get() = if (totalItems > 0) {
            (doneItems.toDouble() / totalItems.toDouble()).coerceIn(0.0, 1.0)
        } else {
            fraction(doneBytes, totalBytes)
        }

    val hasFile: Boolean get() = fileLabel.isNotBlank()

    companion object {
        fun fraction(done: Long, total: Long): Double = when {
            total <= 0L -> 0.0
            done <= 0L -> 0.0
            done >= total -> 1.0
            else -> done.toDouble() / total.toDouble()
        }
    }
}
