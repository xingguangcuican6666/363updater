package com.github.fanziyun.updater.neoforge

import com.github.fanziyun.updater.platform.Platform
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Path

class NeoForgePlatform : Platform {
    override val gameDir: Path get() = FMLPaths.GAMEDIR.get()
    override val loaderId: String get() = "neoforge"
}
