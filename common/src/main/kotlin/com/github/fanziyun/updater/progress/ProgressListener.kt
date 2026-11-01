package com.github.fanziyun.updater.progress

/**
 * Callback producers use to report work. Implemented by [UpdateProgressTracker] for the UI and by
 * recording fakes in tests, so download and staging code stays free of client dependencies.
 *
 * The contract is deliberately small: announce the file, push absolute byte counts while it streams,
 * then finish it. All calls may come from a background thread.
 */
interface ProgressListener {
    /** Switch the coarse run stage; screens show a localized headline for it. */
    fun phase(phase: UpdatePhase, message: String = "") {}

    /** Replace the free-form status line without changing the phase. */
    fun message(message: String) {}

    /** Begin a unit of work; [totalBytes] of `-1` means the size is not known yet. */
    fun startFile(label: String, totalBytes: Long = -1L)

    /** Absolute bytes transferred for the file announced by the last [startFile]. */
    fun fileBytes(doneBytes: Long)

    /** Finish the current file and fold [bytes] into the overall totals. */
    fun finishFile(label: String = "", bytes: Long = 0L)

    companion object {
        /** Listener that discards everything; used where reporting is not wanted. */
        val NONE: ProgressListener = object : ProgressListener {
            override fun startFile(label: String, totalBytes: Long) = Unit
            override fun fileBytes(doneBytes: Long) = Unit
            override fun finishFile(label: String, bytes: Long) = Unit
        }
    }
}
