package com.github.fanziyun.updater.mixin;

import com.github.fanziyun.updater.handoff.HandoffChildSession;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public abstract class FastRestartWindowMixin {
    @Inject(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J")
    )
    private void updater363_hideChildWindow(
        WindowEventHandler eventHandler,
        ScreenManager screenManager,
        DisplayData displayData,
        String fullscreenVideoMode,
        String title,
        CallbackInfo callback
    ) {
        if (HandoffChildSession.INSTANCE.getActive()) GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
    }
}
