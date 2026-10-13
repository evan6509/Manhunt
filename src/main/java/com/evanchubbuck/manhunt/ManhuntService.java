package com.evanchubbuck.manhunt;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.damagesource.DamageSource;

import java.util.UUID;

import java.util.function.Consumer;

final class ManhuntService {
    private ManhuntService() {}

    static boolean start(MinecraftServer server, ServerPlayer runner) {
        return start(server, runner, null);
    }

    static boolean start(MinecraftServer server, ServerPlayer runner, UUID runId) {
        ManhuntState state = ManhuntState.get(server);
        if (state.hunt().isPresent() || !ManhuntNetwork.canStart(server, runId)) return false;
        state.start(runner, runId == null ? UUID.randomUUID() : runId);
        if (!ManhuntNetwork.changed(server)) {
            state.stop(server);
            return false;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            onPlayerReady(player);
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal(
                "Manhunt started! " + runner.getGameProfile().name()
                        + " is the runner. Everyone else is a hunter. Compasses update every 60 seconds."
                        + " The locator bar is disabled during this hunt."), false);
        return true;
    }

    static boolean stop(MinecraftServer server) {
        return finish(server, "stopped");
    }

    static boolean finish(MinecraftServer server, String outcome) {
        ManhuntState state = ManhuntState.get(server);
        if (state.hunt().isEmpty()) return false;
        String runnerName = state.hunt().orElseThrow().runnerName();
        if (ManhuntNetwork.enabled(server)) state.complete(server, outcome);
        else state.stop(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            refreshCompasses(state, player);
        }
        String message = switch (outcome) {
            case "runner_died" -> "The hunters win! " + runnerName + " died.";
            case "runner_won" -> runnerName + " wins! The runner defeated the Ender Dragon.";
            default -> "Manhunt stopped.";
        };
        server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
        ManhuntNetwork.changed(server);
        return true;
    }

    static void afterDeath(LivingEntity entity, DamageSource damageSource) {
        if (!(entity.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
        var server = level.getServer();
        var state = ManhuntState.get(server);
        if (entity instanceof ServerPlayer player && state.isRunner(player)) finish(server, "runner_died");
        else if (entity instanceof EnderDragon dragon) runnerDefeatedDragon(server, dragon);
    }

    static boolean runnerDefeatedDragon(MinecraftServer server, EnderDragon dragon) {
        if (dragon.getHealth() > 0 && dragon.getPhaseManager().getCurrentPhase().getPhase() != EnderDragonPhase.DYING) return false;
        if (dragon.getKillCredit() instanceof ServerPlayer player && ManhuntState.get(server).isRunner(player)) {
            return finish(server, "runner_won");
        }
        return false;
    }

    static void tick(MinecraftServer server) {
        ManhuntNetwork.tick(server);
        ManhuntState state = ManhuntState.get(server);
        var active = state.hunt();
        if (active.isEmpty()) {
            // Retire old compasses when taken out of a closed chest after a hunt ends.
            if (server.getTickCount() % 20 == 0) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    refreshCompasses(state, player);
                }
            }
            return;
        }
        state.disableLocatorBar(server);
        // Also recognize the dragon's special DYING phase, which retains 1 HP during its final flight.
        for (var level : server.getAllLevels()) {
            for (var dragon : level.getDragons()) {
                if ((dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.DYING || dragon.getHealth() <= 0)
                        && runnerDefeatedDragon(server, dragon)) return;
            }
        }
        ServerPlayer runner = server.getPlayerList().getPlayer(active.get().runnerId());
        boolean snapshotUpdated = state.tick(runner);
        // Also apply the stored snapshot to compasses picked up or taken out of storage.
        if (snapshotUpdated || state.ticksUntilUpdate() % 20 == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                refreshCompasses(state, player);
            }
        }
    }

    static void onServerStarted(MinecraftServer server) {
        ManhuntState.get(server).disableLocatorBar(server);
        ManhuntNetwork.onServerStarted(server);
    }

    static void onPlayerReady(ServerPlayer player) {
        ManhuntNetwork.onPlayerReady(player.level().getServer());
        ManhuntState state = ManhuntState.get(player.level().getServer());
        refreshCompasses(state, player);
        if (state.hunt().isEmpty()) return;
        if (state.isRunner(player)) {
            player.sendSystemMessage(Component.literal("You are the runner! Everyone else is hunting you."));
        } else {
            giveCompass(player);
            player.sendSystemMessage(Component.literal("You are a hunter. Your compass tracks "
                    + state.hunt().orElseThrow().runnerName() + "'s last recorded location."));
        }
    }

    /** Idempotent for compasses already carried, including the offhand and cursor. */
    static boolean giveCompass(ServerPlayer player) {
        ManhuntState state = ManhuntState.get(player.level().getServer());
        if (state.hunt().isEmpty() || state.isRunner(player)) return false;
        refreshCompasses(state, player);
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (TrackingCompass.isTracking(player.getInventory().getItem(slot))) return true;
        }
        if (TrackingCompass.isTracking(player.containerMenu.getCarried())) return true;

        ItemStack compass = TrackingCompass.create(state.hunt().orElseThrow());
        // Inventory.add silently consumes uninsertable stacks for creative players.
        // No matching tracker can be present here, so an empty main slot is required.
        if (player.getInventory().getFreeSlot() >= 0) player.getInventory().add(compass);
        if (!compass.isEmpty()) {
            // Preserve all existing items if the hunter's inventory is full.
            var dropped = player.drop(compass, false, false);
            if (dropped != null) {
                dropped.setTarget(player.getUUID());
                dropped.setNoPickUpDelay();
            }
        }
        player.containerMenu.broadcastChanges();
        return true;
    }

    private static void refreshCompasses(ManhuntState state, ServerPlayer player) {
        Consumer<ItemStack> refresh = stack -> {
            if (!TrackingCompass.isTracking(stack)) return;
            if (state.hunt().isPresent() && !state.isRunner(player)) {
                TrackingCompass.update(stack, state.hunt().orElseThrow());
            } else {
                TrackingCompass.deactivate(stack);
            }
        };
        visit(player.getInventory(), refresh);
        visit(player.getEnderChestInventory(), refresh);
        // A runner opening a shared chest must not deactivate another hunter's stored compass.
        if (!state.isRunner(player)) {
            for (var slot : player.containerMenu.slots) refresh.accept(slot.getItem());
        }
        refresh.accept(player.containerMenu.getCarried());
        player.containerMenu.broadcastChanges();
    }

    private static void visit(Container container, Consumer<ItemStack> action) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) action.accept(container.getItem(slot));
    }
}
