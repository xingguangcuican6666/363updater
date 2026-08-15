package com.github.fanziyun.updater

import java.util.Properties

object BuildInfo {
    private val properties: Properties by lazy {
        Properties().apply {
            BuildInfo::class.java.getResourceAsStream("/updater363-build.properties")?.use(::load)
                ?: error("Missing updater363-build.properties")
        }
    }

    val minecraftVersion: String get() = properties.getProperty("minecraftVersion")
    val targetProfile: String get() = properties.getProperty("targetProfile")
}
