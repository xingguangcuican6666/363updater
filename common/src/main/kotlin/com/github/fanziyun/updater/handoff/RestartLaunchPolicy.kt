package com.github.fanziyun.updater.handoff

import com.github.fanziyun.updater.data.LaunchMetrics
import java.util.OptionalLong

object RestartLaunchPolicy {
    const val FIRST_TIMEOUT_MS: Long = 300_000L
    const val MIN_TIMEOUT_MS: Long = 180_000L
    const val MAX_TIMEOUT_MS: Long = 600_000L
    const val LOW_MEMORY_MARGIN_BYTES: Long = 1L shl 30

    fun timeoutMillis(metrics: LaunchMetrics): Long {
        if (metrics.lastSuccessfulStartupMillis <= 0L) return FIRST_TIMEOUT_MS
        if (metrics.lastSuccessfulStartupMillis >= MAX_TIMEOUT_MS / 2L) return MAX_TIMEOUT_MS
        return (metrics.lastSuccessfulStartupMillis * 2L).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }

    fun lowMemory(peakCombinedRssBytes: Long, currentRssBytes: OptionalLong = LinuxProcessMetrics.rssBytes(ProcessHandle.current().pid())): Boolean {
        val available = LinuxProcessMetrics.availableMemoryBytes().orElse(Long.MAX_VALUE)
        if (available == Long.MAX_VALUE) return false
        val estimatedChild = maxOf(currentRssBytes.orElse(0L), peakCombinedRssBytes)
        return available < estimatedChild + LOW_MEMORY_MARGIN_BYTES
    }
}
