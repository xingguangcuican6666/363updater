package com.github.fanziyun.updater.mixin;

import com.github.fanziyun.updater.handoff.HandoffChildSession;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuBackend;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Window.class)
public abstract class FastRestartWindowMixin {
    @Inject(
        method = "createGlfwWindow",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J")
    )
    private static void updater363_hideChildWindow(
        int width,
        int height,
        String title,
        long monitor,
        GpuBackend backend,
        CallbackInfoReturnable<Long> callback
    ) {
        if (HandoffChildSession.INSTANCE.getActive()) GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
    }
}
