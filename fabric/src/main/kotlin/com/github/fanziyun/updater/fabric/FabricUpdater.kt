package com.github.fanziyun.updater.fabric

import com.github.fanziyun.updater.UpdaterService
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment

@Environment(EnvType.CLIENT)
class FabricUpdater : ClientModInitializer {
    override fun onInitializeClient() {
        UpdaterService.init()
    }
}
