package com.github.fanziyun.updater.handoff

import java.util.Locale

object FastRestartPlatformPolicy {
    fun supportsProfile(minecraftVersion: String, loader: String, osName: String): Boolean =
        minecraftVersion to loader.lowercase(Locale.ROOT) in SUPPORTED_PROFILES && isSupportedOs(osName)

    fun shouldWaitForOldProcessBeforeCommit(osName: String, oldProcessAlive: Boolean): Boolean =
        oldProcessAlive && normalized(osName).contains("windows")

    private fun isSupportedOs(osName: String): Boolean = normalized(osName).let { name ->
        name.contains("linux") || name.contains("windows")
    }

    private fun normalized(osName: String): String = osName.lowercase(Locale.ROOT)

    private val SUPPORTED_PROFILES = setOf(
        "1.20.1" to "fabric",
        "1.20.1" to "forge",
        "1.21.1" to "fabric",
        "1.21.1" to "neoforge",
        "26.1.2" to "fabric",
        "26.1.2" to "neoforge",
        "26.2" to "fabric",
        "26.2" to "neoforge",
    )
}
