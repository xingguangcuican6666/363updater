package com.github.fanziyun.updater.mixin;

import com.github.fanziyun.updater.handoff.HandoffChildSession;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(net.minecraft.client.Minecraft.class)
public abstract class FastRestartWindowShowMixin {
    @Redirect(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwShowWindow(J)V")
    )
    private void updater363_delayChildWindow(long handle) {
        if (!HandoffChildSession.INSTANCE.getActive()) GLFW.glfwShowWindow(handle);
    }
}
