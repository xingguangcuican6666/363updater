package com.github.fanziyun.updater.screen

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

object ClientScreens {
    fun execute(action: () -> Unit) {
        Minecraft.getInstance().execute(action)
    }

    fun current(): Screen? = Minecraft.getInstance().screen

    fun set(screen: Screen?) {
        Minecraft.getInstance().setScreen(screen)
    }
}
