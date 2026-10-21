package com.evanchubbuck.manhunt;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Linked Hardcore owns real death reports, countdowns, transfers, timers, and resets. */
public final class LinkedHardcoreCompat {
    private static final Map<MinecraftServer, UUID> REPORTED_RUNS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, Set<UUID>> RESPAWNED_HUNTERS = new IdentityHashMap<>();

    private LinkedHardcoreCompat() {}

    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded("linkedhardcore");
    }

    /** The companion must commit its outbox BEFORE Manhunt can commit a runner-death latch. */
    static String prepareDeathReport(UUID runner) {
        return companionCall("preparePlayerDeath", new Class<?>[]{UUID.class}, runner);
    }

    static void validateRecovery(MinecraftServer server) {
        if (!installed()) return;
        String generation = companionCall("currentDeathGeneration", new Class<?>[0]);
        var state = ManhuntState.get(server);
        if (!state.linkedTransferPending() || state.completedRun().isEmpty()
                || !state.completedRun().orElseThrow().outcome().equals("runner_died")) return;
        if (state.linkedDeathGeneration().isEmpty()) {
            throw new IllegalStateException("Latched runner death lacks durable death intent proof. Preserve this world "
                    + "and reconcile the companion/proxy journals before upgrading; never clear the latch to reopen it.");
        }
        if (!state.linkedDeathGeneration().orElseThrow().equals(generation)) {
            throw new IllegalStateException("Latched runner death belongs to another Linked Hardcore generation; preserve the world");
        }
    }

    private static String companionCall(String method, Class<?>[] types, Object... arguments) {
        try {
            return (String) Class.forName("dev.linkedhardcore.fabric.LinkedHardcoreMod")
                    .getMethod(method, types).invoke(null, arguments);
        } catch (java.lang.reflect.InvocationTargetException error) {
            throw new IllegalStateException("Linked Hardcore durable death intent failed", error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Linked Hardcore durable death outbox API is required. Upgrade the companion "
                    + "on every backend before playing; preserve all existing worlds and journals.", error);
        }
    }

    /** Called at the companion's send boundary, independently of death-event listener order. */
    public static boolean allowDeathReport(ServerPlayer player) {
        var server = player.level().getServer();
        var state = ManhuntState.get(server);
        if (state.hunt().isPresent()) {
            if (!state.isRunner(player)) return false;
            ManhuntService.finish(server, "runner_died");
        }
        if (!state.linkedTransferPending()) {
            prepareDeathReport(player.getUUID()); // Ordinary Linked Hardcore also commits before permission.
            return true;
        }
        var completed = state.completedRun().orElse(null);
        if (completed == null || !completed.outcome().equals("runner_died")
                || !completed.runnerId().equals(player.getUUID())
                || completed.runId().equals(REPORTED_RUNS.get(server))) return false;
        prepareDeathReport(player.getUUID());
        REPORTED_RUNS.put(server, completed.runId());
        return true;
    }

    /** Also latch sources when another backend died or the proxy recovered a transfer. */
    public static void prepareTransfer(MinecraftServer server) {
        var state = ManhuntState.get(server);
        if (state.hunt().isPresent()) ManhuntService.finish(server, "linked_transfer");
        if (!state.linkedTransferPending()) {
            state.awaitLinkedTransfer();
            server.overworld().getDataStorage().saveAndJoin();
        }
        holdPlayers(server);
    }

    public static boolean waiting(MinecraftServer server) {
        return ManhuntState.get(server).linkedTransferPending();
    }

    static void holdPlayers(MinecraftServer server) {
        if (!waiting(server)) return;
        for (var player : server.getPlayerList().getPlayers()) holdPlayer(player);
    }

    static void tick(MinecraftServer server) {
        holdPlayers(server);
        var respawned = RESPAWNED_HUNTERS.remove(server);
        var state = ManhuntState.get(server);
        if (respawned == null || state.linkedTransferPending() || state.hunt().isEmpty()) return;
        for (var id : respawned) {
            var player = server.getPlayerList().getPlayer(id);
            if (player != null && player.isAlive() && !state.isRunner(player) && player.isSpectator()) {
                player.setGameMode(GameType.SURVIVAL);
            }
        }
    }

    public static void holdPlayer(ServerPlayer player) {
        // Preserve the actual dead player until Linked Hardcore respawns it through its connection.
        if (player.isAlive() && !player.isSpectator()) player.setGameMode(GameType.SPECTATOR);
    }

    static void afterRespawn(ServerPlayer player, boolean alive) {
        var state = ManhuntState.get(player.level().getServer());
        if (state.linkedTransferPending()) holdPlayer(player);
        else if (installed() && !alive && state.hunt().isPresent() && !state.isRunner(player)) {
            if (player.isSpectator()) player.setGameMode(GameType.SURVIVAL);
            // The vanilla hardcore packet handler sets spectator AFTER the Fabric respawn event.
            // Recheck only these respawned hunters on the next tick, after that handler returns.
            RESPAWNED_HUNTERS.computeIfAbsent(player.level().getServer(), ignored -> new HashSet<>())
                    .add(player.getUUID());
        }
    }

    static void onServerStopped(MinecraftServer server) {
        REPORTED_RUNS.remove(server);
        RESPAWNED_HUNTERS.remove(server);
    }
}
