package com.evanchubbuck.manhunt.mixin;

import com.evanchubbuck.manhunt.LinkedHardcoreCompat;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.linkedhardcore.fabric.net.ProxyMessenger", remap = false)
public abstract class LinkedHardcoreDeathMixin {
    @Inject(method = "sendPlayerDied", at = @At("HEAD"), cancellable = true, remap = false)
    private void manhunt$runnerDeathsOnly(ServerPlayer player, CallbackInfo ci) {
        if (!LinkedHardcoreCompat.allowDeathReport(player)) ci.cancel();
    }
}
