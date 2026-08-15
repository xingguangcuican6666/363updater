package com.github.fanziyun.updater.neoforge

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.UpdaterService
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.gui.IConfigScreenFactory

@Mod(value = Updater.MOD_ID, dist = [Dist.CLIENT])
class NeoForgeUpdater(container: ModContainer) {
    init {
        UpdaterService.init()
        container.registerExtensionPoint(
            IConfigScreenFactory::class.java,
            IConfigScreenFactory { _, parent -> UpdaterService.configScreen(parent) },
        )
    }
}
