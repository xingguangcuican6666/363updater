package com.github.fanziyun.updater.neoforge

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.platform.Platform
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path

class NeoForgePlatform : Platform {
    override val gameDir: Path get() = FMLPaths.GAMEDIR.get()
    override val loaderId: String get() = "neoforge"
    override val selfJar: Path?
        get() = modPath()?.takeIf(Files::isRegularFile)
    override val protectedModPaths: Set<String>
        get() {
            val root = gameDir.toAbsolutePath().normalize()
            val mods = root.resolve("mods")
            val path = modPath()?.toAbsolutePath()?.normalize()?.takeIf { it.startsWith(mods) } ?: return emptySet()
            return setOf("mods/" + mods.relativize(path).joinToString("/"))
        }

    private fun modPath(): Path? = runCatching {
        ModList.get().getModFileById(Updater.MOD_ID).file.filePath
    }.getOrNull()
}
