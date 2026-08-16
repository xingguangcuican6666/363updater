package com.github.fanziyun.updater.handoff

import com.github.fanziyun.updater.screen.ClientScreens
import com.github.fanziyun.updater.screen.HandoffProgressScreen
import net.minecraft.client.gui.screens.TitleScreen

object FastRestartClientHooks {
    @JvmStatic
    fun onTitleScreen(screen: TitleScreen) {
        if (!HandoffChildSession.shouldInterceptTitle) return
        ClientScreens.execute {
            if (ClientScreens.current() === screen && HandoffChildSession.shouldInterceptTitle) {
                ClientScreens.set(HandoffProgressScreen(screen))
                HandoffChildSession.markTitleReady()
            }
        }
    }

    @JvmStatic
    fun onFrame() {
        if (!HandoffChildSession.active) return
        HandoffChildSession.onFramePresented()
        val request = HandoffChildSession.claimShowRequest() ?: return
        FastRestartClients.instance.showChild(request)
        HandoffChildSession.markWindowShown()
    }
}
