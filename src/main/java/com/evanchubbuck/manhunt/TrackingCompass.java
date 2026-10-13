package com.evanchubbuck.manhunt;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;

import java.util.List;
import java.util.Optional;

final class TrackingCompass {
    private static final String MARKER = "manhunt_compass";

    private TrackingCompass() {}

    static boolean isTracking(ItemStack stack) {
        return stack.is(Items.COMPASS)
                && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getBooleanOr(MARKER, false);
    }

    static ItemStack create(ManhuntState.Hunt hunt) {
        ItemStack compass = new ItemStack(Items.COMPASS);
        CustomData.update(DataComponents.CUSTOM_DATA, compass, tag -> tag.putBoolean(MARKER, true));
        update(compass, hunt);
        return compass;
    }

    static void update(ItemStack compass, ManhuntState.Hunt hunt) {
        var target = hunt.lastKnown();
        var pos = target.pos();
        compass.set(DataComponents.CUSTOM_NAME, Component.literal("Manhunt Compass")
                .withStyle(ChatFormatting.GOLD));
        // tracked=false prevents vanilla from erasing the target when no lodestone exists there.
        compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(target), false));
        compass.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Runner: " + hunt.runnerName()).withStyle(ChatFormatting.YELLOW),
                Component.literal("Last known: " + target.dimension().identifier())
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("X: " + pos.getX() + "  Y: " + pos.getY() + "  Z: " + pos.getZ())
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("Updates every 60 seconds").withStyle(ChatFormatting.DARK_GRAY)
        )));
    }

    static void deactivate(ItemStack compass) {
        compass.remove(DataComponents.LODESTONE_TRACKER);
        compass.remove(DataComponents.CUSTOM_NAME);
        compass.remove(DataComponents.LORE);
        CustomData.update(DataComponents.CUSTOM_DATA, compass, tag -> tag.remove(MARKER));
        if (compass.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).isEmpty()) {
            compass.remove(DataComponents.CUSTOM_DATA);
        }
    }
}
