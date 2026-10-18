package com.evanchubbuck.manhunt.mixin;

import com.evanchubbuck.manhunt.LinkedHardcoreCompat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.linkedhardcore.fabric.death.TransferCountdown", remap = false)
public abstract class LinkedHardcoreTransferMixin {
    @Inject(method = {"waitForServer", "start"}, at = @At("HEAD"), remap = false)
    private void manhunt$closeSource(MinecraftServer server, CallbackInfo ci) {
        LinkedHardcoreCompat.prepareTransfer(server);
    }

    @Inject(method = "respawnOnJoin", at = @At("HEAD"), cancellable = true, remap = false)
    private void manhunt$keepSourceClosed(ServerPlayer player, CallbackInfo ci) {
        if (LinkedHardcoreCompat.waiting(player.level().getServer())) {
            LinkedHardcoreCompat.holdPlayer(player);
            ci.cancel();
        }
    }
}
