package com.github.fanziyun.updater.fabric

import com.github.fanziyun.updater.platform.Platform
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

class FabricPlatform : Platform {
    override val gameDir: Path get() = FabricLoader.getInstance().gameDir
    override val loaderId: String get() = "fabric"
}
