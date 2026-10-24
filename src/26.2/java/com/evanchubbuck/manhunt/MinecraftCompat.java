package com.evanchubbuck.manhunt;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Target-specific server-created compass drop; never a predicted client action. */
final class MinecraftCompat {
    private MinecraftCompat() {}

    static ItemEntity dropCompass(ServerPlayer player, ItemStack stack) {
        return player.drop(stack, false, false);
    }
}
