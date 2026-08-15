package com.github.fanziyun.updater.mixin

import com.github.fanziyun.updater.UpdaterService
import com.github.fanziyun.updater.screen.ClientScreens
import com.github.fanziyun.updater.screen.UpdatePromptScreen
import com.github.fanziyun.updater.util.ButtonPlacement
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(TitleScreen::class)
abstract class TitleScreenMixin : Screen(Component.literal("")) {
    @Inject(method = ["init"], at = [At("TAIL")])
    fun updater363_addButton(callback: CallbackInfo) {
        if (!UpdaterService.state.hasUpdate) return
        if (UpdaterService.shouldPrompt()) {
            UpdaterService.markPromptShown()
            ClientScreens.execute {
                if (ClientScreens.current() === this) {
                    ClientScreens.set(UpdatePromptScreen(this))
                }
            }
            return
        }
        val width = 200
        val left = this.width / 2 - width / 2
        val y = ButtonPlacement.belowExistingColumn(children(), height, left, left + width) ?: (height / 4 + 48 + 72)
        addRenderableWidget(
            Button.builder(Component.translatable("menu.updater363.button")) {
                UpdaterService.markPromptShown()
                ClientScreens.set(UpdatePromptScreen(ClientScreens.current()))
            }.bounds(left, y, width, ButtonPlacement.BUTTON_HEIGHT).build()
        )
    }
}
