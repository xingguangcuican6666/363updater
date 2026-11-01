package com.github.fanziyun.updater.util

import java.util.Locale

/** Shared human-readable byte formatting for updater screens. */
object ByteFormat {
    private const val KIB = 1024L
    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * 1024L * 1024L

    fun bytes(value: Long): String = when {
        value < 0L -> "?"
        value >= GIB -> "%.1f GiB".format(Locale.ROOT, value / GIB.toDouble())
        value >= MIB -> "%.1f MiB".format(Locale.ROOT, value / MIB.toDouble())
        value >= KIB -> "%.1f KiB".format(Locale.ROOT, value / KIB.toDouble())
        else -> "$value B"
    }

    /** `12 / 34`; the total is replaced with `?` when it is unknown. */
    fun count(done: Int, total: Int): String = if (total > 0) "$done / $total" else "$done / ?"
}
