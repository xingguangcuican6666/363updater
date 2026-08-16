package com.github.fanziyun.updater.mixin;

import com.github.fanziyun.updater.handoff.FastRestartClientHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class FastRestartMinecraftMixin {
    @Inject(method = "renderFrame", at = @At("TAIL"))
    private void updater363_afterFrame(boolean advanceGameTime, CallbackInfo callback) {
        FastRestartClientHooks.onFrame();
    }
}
