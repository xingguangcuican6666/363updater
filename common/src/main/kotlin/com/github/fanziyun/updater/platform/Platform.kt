package com.github.fanziyun.updater.platform

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.BuildInfo
import java.nio.file.Path
import java.util.ServiceLoader

interface Platform {
    val gameDir: Path
    val loaderId: String
    val minecraftVersion: String get() = BuildInfo.minecraftVersion
    val protectedModPaths: Set<String> get() = emptySet()
    val selfJar: Path? get() = null

    companion object {
        val INSTANCE: Platform by lazy {
            ServiceLoader.load(Platform::class.java, Platform::class.java.classLoader)
                .findFirst()
                .orElseThrow {
                    IllegalStateException("No updater platform implementation found")
                }
                .also { Updater.LOGGER.info("Updater platform: {} {}", it.loaderId, it.minecraftVersion) }
        }
    }
}
