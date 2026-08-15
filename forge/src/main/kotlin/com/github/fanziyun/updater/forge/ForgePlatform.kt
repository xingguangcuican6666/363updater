package com.github.fanziyun.updater.forge

import com.github.fanziyun.updater.platform.Platform
import net.minecraftforge.fml.loading.FMLPaths
import java.nio.file.Path

class ForgePlatform : Platform {
    override val gameDir: Path get() = FMLPaths.GAMEDIR.get()
    override val loaderId: String get() = "forge"
}
