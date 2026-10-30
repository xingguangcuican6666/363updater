package com.github.fanziyun.updater.progress

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe publisher of updater progress.
 *
 * Producers run on `363Updater-Worker` (downloads, staging, file writes) while screens read from the
 * render thread, so every access goes through a volatile snapshot. The snapshot is immutable and is
 * replaced wholesale; a reader never sees a half-updated combination of file and overall counters.
 *
 * Byte counters use monotonic accumulators so a producer that reports deltas cannot push the overall
 * total backwards. Screens poll this in `tick`/`render`; no callbacks are held across threads.
 */
object UpdateProgressTracker {
    private val snapshot = AtomicReference(UpdateProgress())
    private val byteAccumulator = AtomicLong(0L)
    private val itemAccumulator = AtomicLong(0L)

    val progress: UpdateProgress get() = snapshot.get()

    /**
     * A listener that folds producer callbacks into the shared snapshot. Downloads announce their
     * expected size, so the per-file meter is exact; an unknown size (`-1`) leaves the file bar at
     * zero while the indeterminate phase caption carries the meaning.
     *
     * This is exposed as a separate object rather than implemented by the tracker itself so the
     * interface method names cannot shadow the tracker's own `fileBytes(Long)` overload and recurse.
     */
    val listener: ProgressListener = object : ProgressListener {
        override fun phase(phase: UpdatePhase, message: String) {
            this@UpdateProgressTracker.phase(phase, message)
        }

        override fun message(message: String) {
            this@UpdateProgressTracker.message(message)
        }

        override fun startFile(label: String, totalBytes: Long) {
            file(label, if (totalBytes < 0L) 0L else totalBytes)
        }

        override fun fileBytes(doneBytes: Long) {
            this@UpdateProgressTracker.fileBytes(doneBytes)
        }

        override fun finishFile(label: String, bytes: Long) {
            completeItem(label, bytes)
        }
    }

    /** True while a run is in flight; screens use it to block leaving mid-write. */
    val active: Boolean get() = progress.phase != UpdatePhase.IDLE &&
        progress.phase != UpdatePhase.DONE && progress.phase != UpdatePhase.FAILED

    fun reset(totalItems: Int = 0, totalBytes: Long = 0L) {
        byteAccumulator.set(0L)
        itemAccumulator.set(0L)
        snapshot.set(
            UpdateProgress(
                phase = UpdatePhase.IDLE,
                totalItems = totalItems.coerceAtLeast(0),
                totalBytes = totalBytes.coerceAtLeast(0L),
            ),
        )
    }

    fun phase(phase: UpdatePhase, message: String = "") = mutate { it.copy(phase = phase, message = message) }

    fun message(message: String) = mutate { it.copy(message = message) }

    /** Announce the file currently being processed, clearing per-file byte counters. */
    fun file(label: String, totalBytes: Long = 0L) = mutate {
        it.copy(
            fileLabel = label,
            fileDoneBytes = 0L,
            fileTotalBytes = totalBytes.coerceAtLeast(0L),
        )
    }

    /** Report absolute progress inside the current file. Values never move the overall counters. */
    fun fileBytes(doneBytes: Long) = mutate {
        it.copy(fileDoneBytes = doneBytes.coerceAtLeast(0L))
    }

    fun addOverallBytes(deltaBytes: Long) {
        if (deltaBytes == 0L) return
        val next = byteAccumulator.addAndGet(deltaBytes).coerceAtLeast(0L)
        mutate { it.copy(doneBytes = next) }
    }

    /** Mark one file (or one staging step) finished. */
    fun completeItem(label: String = "", bytes: Long = 0L) {
        if (bytes != 0L) addOverallBytes(bytes)
        val next = itemAccumulator.incrementAndGet()
        mutate { it.copy(doneItems = next.toInt(), fileLabel = label, fileDoneBytes = 0L, fileTotalBytes = 0L) }
    }

    fun done(message: String = "") {
        mutate {
            it.copy(
                phase = UpdatePhase.DONE,
                message = message,
                fileLabel = "",
                fileDoneBytes = it.fileTotalBytes,
                doneItems = if (it.totalItems > 0) it.totalItems else it.doneItems,
                doneBytes = if (it.totalBytes > 0) it.totalBytes else it.doneBytes,
            )
        }
    }

    fun failed(message: String) {
        mutate { it.copy(phase = UpdatePhase.FAILED, message = message, fileLabel = "", fileDoneBytes = 0L) }
    }

    private fun mutate(transform: (UpdateProgress) -> UpdateProgress) {
        while (true) {
            val current = snapshot.get()
            if (snapshot.compareAndSet(current, transform(current))) return
        }
    }
}
