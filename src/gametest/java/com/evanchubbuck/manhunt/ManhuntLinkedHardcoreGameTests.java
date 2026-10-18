package com.evanchubbuck.manhunt;

import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Invoked only by -PlinkedHardcoreJar, against the actual transformed companion classes. */
public final class ManhuntLinkedHardcoreGameTests {
    @GameTest
    public void hunterReportsAreSuppressedAndRunnerReportsOnce(GameTestHelper helper) throws Exception {
        try (var f = new ManhuntGameTests.Fixture(helper, true)) {
            helper.assertTrue(LinkedHardcoreCompat.installed(), "Actual Linked Hardcore must be loaded");
            var runner = f.player();
            var hunter = f.player();
            var messenger = messenger();
            ManhuntService.start(f.server, runner);
            report(messenger, hunter);
            helper.assertTrue(pending(messenger).isEmpty(), "Hunter death never queues a proxy death report");
            helper.assertTrue(f.state.hunt().isPresent(), "Hunter death leaves the hunt active");
            report(messenger, runner); // Companion listener runs before Manhunt's listener.
            helper.assertTrue(pending(messenger).containsKey(runner.getUUID()), "Only the runner is reported");
            helper.assertTrue(f.state.linkedTransferPending(), "Runner death closes the source");
            helper.assertTrue(f.state.hunt().isEmpty(), "Runner death ends the hunt");
            pending(messenger).clear();
            report(messenger, runner);
            report(messenger, hunter);
            helper.assertTrue(pending(messenger).isEmpty(), "Duplicate and teammate reports stay suppressed");
        }
        helper.succeed();
    }

    @GameTest
    public void manhuntListenerCanFinishBeforeCompanionReports(GameTestHelper helper) throws Exception {
        try (var f = new ManhuntGameTests.Fixture(helper, true)) {
            var runner = f.player();
            f.player();
            var messenger = messenger();
            ManhuntService.start(f.server, runner);
            ManhuntService.afterDeath(runner, runner.damageSources().generic());
            report(messenger, runner);
            helper.assertTrue(pending(messenger).containsKey(runner.getUUID()),
                    "Finishing the hunt first must retain the original runner death report");
        }
        helper.succeed();
    }

    @GameTest
    public void actualDeathEventLeavesHuntersPlaying(GameTestHelper helper) throws Exception {
        try (var f = new ManhuntGameTests.Fixture(helper, true)) {
            var runner = f.player();
            var hunter = f.player();
            hunter.setGameMode(GameType.SURVIVAL);
            ManhuntService.start(f.server, runner);
            ServerLivingEntityEvents.AFTER_DEATH.invoker().afterDeath(hunter, hunter.damageSources().generic());
            helper.assertTrue(f.state.hunt().isPresent() && !f.state.linkedTransferPending(),
                    "Real registered death listeners must ignore hunters");
            hunter.getInventory().clearContent();
            hunter.setGameMode(GameType.SPECTATOR);
            ServerPlayerEvents.AFTER_RESPAWN.invoker().afterRespawn(hunter, hunter, false);
            helper.assertValueEqual(hunter.gameMode(), GameType.SURVIVAL,
                    "A hardcore hunter respawn returns to survival");
            hunter.setGameMode(GameType.SPECTATOR); // Vanilla hardcore applies this after the Fabric callback.
            ManhuntService.tick(f.server);
            helper.assertValueEqual(hunter.gameMode(), GameType.SURVIVAL,
                    "Post-callback vanilla hardcore spectator mode is repaired on the next tick");
            helper.assertTrue(hunter.getInventory().contains(stack -> stack.is(Items.COMPASS)),
                    "Respawning hunter receives its tracking compass");
        }
        helper.succeed();
    }

    @GameTest
    public void realDeathsAndRespawnProduceOnlyOneRunnerFrame(GameTestHelper helper) throws Exception {
        try (var f = new ManhuntGameTests.Fixture(helper, true)) {
            var runner = f.player();
            var hunter = f.player();
            ManhuntService.start(f.server, runner);
            hunter.hurtServer(helper.getLevel(), hunter.damageSources().generic(), Float.MAX_VALUE);
            helper.assertFalse(hunter.isAlive(), "Hunter actually dies in Minecraft");
            helper.assertValueEqual(f.deathFrames(), 0, "Hunter death emits no PLAYER_DIED frame");
            hunter = f.respawn(hunter);
            helper.assertTrue(hunter.isAlive() && !hunter.isSpectator(), "Hunter actually respawns into play");
            helper.assertTrue(hunter.getInventory().contains(stack -> stack.is(Items.COMPASS)),
                    "Real respawn restores a compass");
            runner.hurtServer(helper.getLevel(), runner.damageSources().generic(), Float.MAX_VALUE);
            helper.assertFalse(runner.isAlive(), "Runner actually dies in Minecraft");
            helper.assertTrue(f.state.linkedTransferPending() && hunter.isSpectator(), "Survivor waits for transfer");
            helper.assertValueEqual(f.deathFrames(), 1, "Only the original runner death reaches the proxy channel");
        }
        helper.succeed();
    }

    @GameTest
    public void waitAndCountdownCloseSourceAndKeepReconnectsSpectating(GameTestHelper helper) throws Exception {
        try (var f = new ManhuntGameTests.Fixture(helper, true)) {
            var runner = f.player();
            var hunter = f.player();
            ManhuntService.start(f.server, runner);
            var countdown = countdown(new AtomicInteger());
            invoke(countdown, "waitForServer", MinecraftServer.class, f.server);
            helper.assertTrue(f.state.hunt().isEmpty() && f.state.linkedTransferPending(),
                    "A proxy wait from another backend also closes this hunt");
            helper.assertTrue(runner.isSpectator() && hunter.isSpectator(), "Source players spectate");
            invoke(countdown, "respawnOnJoin", ServerPlayer.class, hunter);
            helper.assertTrue(hunter.isSpectator(), "Companion join code cannot reopen the source");
            var newcomer = f.player();
            invoke(countdown, "respawnOnJoin", ServerPlayer.class, newcomer);
            helper.assertTrue(newcomer.isSpectator(), "A late arrival stays spectating");
            invoke(countdown, "start", MinecraftServer.class, f.server);
            f.expectFailure(runner, "manhunt start " + runner.getGameProfile().name(), true);
            helper.assertFalse(ManhuntService.start(f.server, runner), "Internal starts are also blocked");
            helper.assertFalse(ManhuntService.stop(f.server), "Stop cannot clear a pending transfer");
            helper.assertValueEqual(f.command(runner, "manhunt status", false), 1,
                    "Status remains available while waiting for Linked Hardcore");
        }
        helper.succeed();
    }

    @GameTest
    public void transferWithoutActiveHuntStillLocksSource(GameTestHelper helper) throws Exception {
        try (var f = new ManhuntGameTests.Fixture(helper, true)) {
            var player = f.player();
            invoke(countdown(new AtomicInteger()), "start", MinecraftServer.class, f.server);
            helper.assertTrue(f.state.linkedTransferPending() && player.isSpectator(),
                    "An idle source is also closed when the proxy prepares a transfer");
            helper.assertFalse(ManhuntService.start(f.server, player), "No hunt may start in a transferring world");
        }
        helper.succeed();
    }

    @GameTest
    public void completionSurvivesDiskReloadAndFreshWorldStartsNormally(GameTestHelper helper) throws Exception {
        try (var f = new ManhuntGameTests.Fixture(helper, true)) {
            var runner = f.player();
            ManhuntService.start(f.server, runner);
            ManhuntService.afterDeath(runner, runner.damageSources().generic());
            var nativeFile = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(
                    net.minecraft.world.level.Level.OVERWORLD,
                    f.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT))
                    .resolve("data/manhunt/state.dat");
            var tag = net.minecraft.nbt.NbtIo.readCompressed(nativeFile, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            var loaded = ManhuntState.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,
                    tag.getCompoundOrEmpty("data")).getOrThrow();
            helper.assertTrue(loaded.linkedTransferPending() && loaded.completedRun().isPresent(),
                    "Native disk save retains the source lock and the completed runner");
            f.state.restoreNetworkCheckpoint(loaded);
            helper.assertFalse(ManhuntService.start(f.server, runner), "Reloaded source cannot restart");
            f.state.restoreNetworkCheckpoint(new ManhuntState()); // The destination has a different world save.
            invoke(countdown(new AtomicInteger()), "respawnOnJoin", ServerPlayer.class, runner);
            helper.assertValueEqual(runner.gameMode(), GameType.SURVIVAL, "Fresh destination restores survival");
            helper.assertTrue(ManhuntService.start(f.server, runner), "Fresh world accepts its first hunt");
            var legacy = ManhuntState.CODEC.encodeStart(JsonOps.INSTANCE, new ManhuntState()).getOrThrow().getAsJsonObject();
            legacy.remove("linked_transfer_pending");
            helper.assertFalse(ManhuntState.CODEC.parse(JsonOps.INSTANCE, legacy).getOrThrow().linkedTransferPending(),
                    "Existing standalone saves remain compatible");
        }
        helper.succeed();
    }

    @GameTest
    public void companionCountdownStillNotifiesItsProxy(GameTestHelper helper) throws Exception {
        try (var f = new ManhuntGameTests.Fixture(helper, true)) {
            f.player();
            var notified = new AtomicInteger();
            var countdown = countdown(notified);
            invoke(countdown, "start", MinecraftServer.class, f.server);
            invoke(countdown, "tick", MinecraftServer.class, f.server);
            invoke(countdown, "tick", MinecraftServer.class, f.server);
            helper.assertValueEqual(notified.get(), 1, "The companion still sends exactly one TRANSFER_READY");
        }
        helper.succeed();
    }

    @GameTest
    public void competingManhuntControllerIsRejected(GameTestHelper helper) {
        try (var f = new ManhuntGameTests.Fixture(helper, true)) {
            try {
                ManhuntNetwork.configure(f.server, new ManhuntNetwork.Config(true, "a", "network"));
                throw helper.assertionException("Competing world controllers were accepted");
            } catch (IllegalStateException expected) {
                helper.assertTrue(expected.getMessage().contains("Linked Hardcore owns"), "Conflict explains the fix");
            }
            helper.assertFalse(ManhuntNetwork.enabled(f.server), "Rejected bridge remains disabled");
        }
        helper.succeed();
    }

    @GameTest
    public void idleDeathReportsRetainOrdinaryLinkedHardcoreBehavior(GameTestHelper helper) throws Exception {
        try (var f = new ManhuntGameTests.Fixture(helper, true)) {
            var player = f.player();
            var messenger = messenger();
            report(messenger, player);
            helper.assertTrue(pending(messenger).containsKey(player.getUUID()),
                    "Outside a hunt the companion retains its ordinary linked life pool");
        }
        helper.succeed();
    }

    @GameTest
    public void manualStopAndVictoryDoNotManufactureDeaths(GameTestHelper helper) throws Exception {
        try (var f = new ManhuntGameTests.Fixture(helper, true)) {
            var runner = f.player();
            ManhuntService.start(f.server, runner);
            helper.assertTrue(ManhuntService.stop(f.server), "Manual stop ends the hunt locally");
            helper.assertFalse(f.state.linkedTransferPending(), "Manual stop does not request a linked death transfer");
            helper.assertTrue(ManhuntService.start(f.server, runner), "Another local hunt can start after stop");
            helper.assertTrue(ManhuntService.finish(f.server, "runner_won"), "Runner victory ends the hunt locally");
            helper.assertFalse(f.state.linkedTransferPending(), "Victory does not fabricate a proxy death");
        }
        helper.succeed();
    }

    @GameTest public void commandsAndInitialCompasses(GameTestHelper helper) throws Exception {
        new ManhuntGameTests().startCommandGivesEveryOtherPlayerSnapshot(helper);
    }
    @GameTest public void minuteSnapshots(GameTestHelper helper) {
        new ManhuntGameTests().locationChangesOnlyAtMinuteBoundary(helper);
    }
    @GameTest public void lateHuntersAndReplacementCompasses(GameTestHelper helper) throws Exception {
        new ManhuntGameTests().replacementAndLateJoinUseStoredLocation(helper);
    }

    private static Object config() throws Exception {
        return Class.forName("dev.linkedhardcore.fabric.config.ModConfig")
                .getConstructor(String.class, int.class, int.class).newInstance("a", 10, 0);
    }

    private static Object messenger() throws Exception {
        var config = config();
        return Class.forName("dev.linkedhardcore.fabric.net.ProxyMessenger")
                .getConstructor(config.getClass()).newInstance(config);
    }

    private static void report(Object messenger, ServerPlayer player) throws Exception {
        invoke(messenger, "sendPlayerDied", ServerPlayer.class, player);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Long> pending(Object messenger) throws Exception {
        var field = messenger.getClass().getDeclaredField("pendingAcks");
        field.setAccessible(true);
        return (Map<UUID, Long>) field.get(messenger);
    }

    private static Object countdown(AtomicInteger notified) throws Exception {
        var config = config();
        var notifierType = Class.forName("dev.linkedhardcore.fabric.death.TransferReadyNotifier");
        var notifier = Proxy.newProxyInstance(notifierType.getClassLoader(), new Class<?>[]{notifierType},
                (proxy, method, args) -> {
                    if (method.getName().equals("notifyTransferReady")) notified.incrementAndGet();
                    return null;
                });
        return Class.forName("dev.linkedhardcore.fabric.death.TransferCountdown")
                .getConstructor(config.getClass(), notifierType).newInstance(config, notifier);
    }

    private static void invoke(Object target, String method, Class<?> argumentType, Object argument) throws Exception {
        target.getClass().getMethod(method, argumentType).invoke(target, argument);
    }
}
