package com.github.fanziyun.updater.forge

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.UpdaterService
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.ConfigScreenHandler
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.loading.FMLEnvironment

@Mod(Updater.MOD_ID)
class ForgeUpdater {
    init {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            UpdaterService.init()
            ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory::class.java) {
                ConfigScreenHandler.ConfigScreenFactory { _, parent -> UpdaterService.configScreen(parent) }
            }
        }
    }
}
