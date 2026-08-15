package com.github.fanziyun.updater.reload

import net.minecraft.client.Minecraft
import java.util.concurrent.CompletableFuture

object MinecraftReloadBridge {
    fun reloadOptions() {
        Minecraft.getInstance().options.load()
    }

    fun reloadResources(): CompletableFuture<*> = Minecraft.getInstance().reloadResourcePacks()
}
