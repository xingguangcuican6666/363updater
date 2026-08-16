package com.github.fanziyun.updater.fabric

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.platform.Platform
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

class FabricPlatform : Platform {
    override val gameDir: Path get() = FabricLoader.getInstance().gameDir
    override val loaderId: String get() = "fabric"
    override val protectedModPaths: Set<String>
        get() {
            val root = gameDir.toAbsolutePath().normalize()
            return FabricLoader.getInstance().getModContainer(Updater.MOD_ID).orElse(null)
                ?.origin?.paths.orEmpty()
                .mapNotNull { path ->
                    val normalized = path.toAbsolutePath().normalize()
                    normalized.takeIf { it.startsWith(root.resolve("mods")) }
                        ?.let(root::relativize)
                        ?.joinToString("/")
                }
                .toSet()
        }
    override val selfJar: Path?
        get() = FabricLoader.getInstance().getModContainer(Updater.MOD_ID).orElse(null)
            ?.origin?.paths.orEmpty()
            .firstOrNull { java.nio.file.Files.isRegularFile(it) }
}
