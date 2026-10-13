package com.evanchubbuck.manhunt;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ManhuntGameTests {
    @GameTest
    public void startCommandGivesEveryOtherPlayerSnapshot(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            var first = f.player();
            var second = f.player();
            runner.snapTo(100, 70, -200);
            helper.assertTrue(f.command(runner, "manhunt start " + runner.getGameProfile().name(), true) == 1,
                    "Start command accepts an online player name");
            helper.assertTrue(!hasCompass(runner), "Runner receives no tracking compass");
            helper.assertTrue(hasCompass(first) && hasCompass(second), "Both other players are hunters");
            var target = GlobalPos.of(runner.level().dimension(), new BlockPos(100, 70, -200));
            helper.assertValueEqual(targetOf(compass(first)), target, "Immediate snapshot is exact");
            helper.assertValueEqual(targetOf(compass(second)), target, "Every hunter gets same snapshot");
            helper.assertFalse(compass(first).get(DataComponents.LODESTONE_TRACKER).tracked(),
                    "Vanilla must not require a lodestone block");
        }
        helper.succeed();
    }

    @GameTest
    public void locationChangesOnlyAtMinuteBoundary(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            var hunter = f.player();
            ManhuntService.start(f.server, runner);
            GlobalPos original = targetOf(compass(hunter));
            runner.snapTo(123, 80, 456);
            for (int i = 0; i < ManhuntState.UPDATE_INTERVAL_TICKS - 1; i++) ManhuntService.tick(f.server);
            helper.assertValueEqual(targetOf(compass(hunter)), original, "No live movement leaks before 1,200 ticks");
            ManhuntService.tick(f.server);
            helper.assertValueEqual(targetOf(compass(hunter)),
                    GlobalPos.of(runner.level().dimension(), new BlockPos(123, 80, 456)), "Minute boundary updates position");
            runner.snapTo(-50, 65, 12);
            for (int i = 0; i < ManhuntState.UPDATE_INTERVAL_TICKS - 1; i++) ManhuntService.tick(f.server);
            helper.assertValueEqual(targetOf(compass(hunter)).pos(), new BlockPos(123, 80, 456),
                    "Second interval also waits a full minute");
            ManhuntService.tick(f.server);
            helper.assertValueEqual(targetOf(compass(hunter)).pos(), new BlockPos(-50, 65, 12), "Second snapshot updates");
        }
        helper.succeed();
    }

    @GameTest
    public void replacementAndLateJoinUseStoredLocation(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            var hunter = f.player();
            ManhuntService.start(f.server, runner);
            GlobalPos original = targetOf(compass(hunter));
            runner.snapTo(900, 75, 900);
            hunter.getInventory().clearContent();
            helper.assertTrue(f.command(hunter, "manhunt compass", false) == 1, "Non-operator hunter gets replacement");
            helper.assertValueEqual(targetOf(compass(hunter)), original, "Replacement never samples live position");
            f.command(hunter, "manhunt compass", false);
            helper.assertValueEqual(compassCount(hunter), 1, "Repeated command reuses the carried compass");
            for (int i = 0; i < 3; i++) {
                hunter.getInventory().clearContent();
                helper.assertValueEqual(f.command(hunter, "manhunt compass", false), 1,
                        "Lost compasses can be replaced immediately without a cooldown");
                helper.assertValueEqual(targetOf(compass(hunter)), original, "Repeated replacements use the stored snapshot");
            }
            var newcomer = f.player(); // Actual Fabric connection join event provisions this player.
            helper.assertTrue(hasCompass(newcomer), "Late join automatically receives a compass");
            helper.assertValueEqual(targetOf(compass(newcomer)), original, "Late join sees only last snapshot");
            ManhuntService.tick(f.server);
            helper.assertValueEqual(f.state.ticksUntilUpdate(), ManhuntState.UPDATE_INTERVAL_TICKS - 1,
                    "Join and replacement do not reset the timer");
        }
        helper.succeed();
    }

    @GameTest
    public void offlineRunnerKeepsLastKnownLocation(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            var hunter = f.player();
            ManhuntService.start(f.server, runner);
            GlobalPos original = targetOf(compass(hunter));
            f.server.getPlayerList().remove(runner);
            runner.snapTo(500, 90, 500);
            for (int i = 0; i < ManhuntState.UPDATE_INTERVAL_TICKS; i++) ManhuntService.tick(f.server);
            helper.assertValueEqual(targetOf(compass(hunter)), original, "Disconnect preserves snapshot");
            helper.assertTrue(f.state.hunt().isPresent(), "Disconnect does not end the hunt");
        }
        helper.succeed();
    }

    @GameTest
    public void respawnEventRestoresHuntersCompass(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            var hunter = f.player();
            ManhuntService.start(f.server, runner);
            GlobalPos original = targetOf(compass(hunter));
            hunter.getInventory().clearContent();
            runner.snapTo(400, 80, 400);
            ServerPlayerEvents.AFTER_RESPAWN.invoker().afterRespawn(hunter, hunter, false);
            helper.assertTrue(hasCompass(hunter), "Respawn callback restores compass");
            helper.assertValueEqual(targetOf(compass(hunter)), original, "Respawn retains previous snapshot");
            ServerPlayerEvents.AFTER_RESPAWN.invoker().afterRespawn(runner, runner, false);
            helper.assertFalse(hasCompass(runner), "Runner respawn does not receive a compass");
        }
        helper.succeed();
    }

    @GameTest
    public void stopPreservesOrdinaryCompassAndItems(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            var hunter = f.player();
            ItemStack ordinary = new ItemStack(Items.COMPASS);
            hunter.getInventory().setItem(5, ordinary);
            hunter.getInventory().setItem(6, new ItemStack(Items.DIAMOND, 7));
            ManhuntService.start(f.server, runner);
            ItemStack tracker = compass(hunter);
            hunter.getEnderChestInventory().setItem(0, tracker.copy());
            hunter.containerMenu.setCarried(tracker.copy());
            f.command(runner, "manhunt stop", true);
            helper.assertTrue(f.state.hunt().isEmpty(), "Stop ends hunt");
            helper.assertTrue(!TrackingCompass.isTracking(tracker), "Tracking marker retired");
            helper.assertTrue(tracker.get(DataComponents.LODESTONE_TRACKER) == null, "Needle returns to ordinary behavior");
            helper.assertTrue(!TrackingCompass.isTracking(hunter.getEnderChestInventory().getItem(0)), "Ender chest retired");
            helper.assertTrue(!TrackingCompass.isTracking(hunter.containerMenu.getCarried()), "Cursor compass retired");
            helper.assertTrue(ordinary.get(DataComponents.CUSTOM_DATA) == null && ordinary.get(DataComponents.CUSTOM_NAME) == null,
                    "Ordinary compass never modified");
            helper.assertValueEqual(hunter.getInventory().getItem(6).getCount(), 7, "Existing diamonds preserved");
        }
        helper.succeed();
    }

    @GameTest
    public void permissionsAndInvalidStartsAreRejected(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            var hunter = f.player();
            f.expectFailure(hunter, "manhunt start @s", false);
            helper.assertTrue(f.state.hunt().isEmpty(), "Non-operator cannot start hunt");
            f.expectFailure(runner, "manhunt start ThisPlayerIsNotOnline", true);
            helper.assertTrue(f.state.hunt().isEmpty(), "Unknown player does not start hunt");
            f.command(runner, "manhunt start @s", true);
            var original = f.state.hunt().orElseThrow();
            f.expectFailure(hunter, "manhunt start @s", true);
            helper.assertValueEqual(f.state.hunt().orElseThrow(), original, "Active hunt is not silently overwritten");
            f.expectFailure(hunter, "manhunt stop", false);
            f.expectFailure(runner, "manhunt compass", false);
            helper.assertTrue(!hasCompass(runner), "Runner cannot request tracking compass");
            helper.assertTrue(f.command(hunter, "manhunt status", false) == 1, "Everyone can view status");
        }
        helper.succeed();
    }

    @GameTest
    public void netherSnapshotHasDimensionAndCoordinates(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            var hunter = f.player();
            ManhuntService.start(f.server, runner);
            var original = targetOf(compass(hunter));
            var nether = f.server.getLevel(Level.NETHER);
            helper.assertTrue(nether != null, "Nether exists");
            runner.setServerLevel(nether);
            runner.snapTo(30, 64, -40);
            for (int i = 0; i < ManhuntState.UPDATE_INTERVAL_TICKS - 1; i++) ManhuntService.tick(f.server);
            helper.assertValueEqual(targetOf(compass(hunter)), original, "Portal travel does not reveal position early");
            ManhuntService.tick(f.server);
            helper.assertValueEqual(targetOf(compass(hunter)), GlobalPos.of(Level.NETHER, new BlockPos(30, 64, -40)),
                    "Snapshot follows runner's new dimension");
            var lore = compass(hunter).getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().stream()
                    .map(component -> component.getString()).toList();
            helper.assertTrue(lore.stream().anyMatch(line -> line.contains("minecraft:the_nether")), "Tooltip identifies dimension");
            helper.assertTrue(lore.stream().anyMatch(line -> line.contains("X: 30") && line.contains("Z: -40")), "Tooltip identifies coordinates");
            runner.setServerLevel(helper.getLevel());
        }
        helper.succeed();
    }

    @GameTest
    public void savedStateRoundTripRetainsRunnerAndSnapshot(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            var hunter = f.player();
            ManhuntService.start(f.server, runner);
            var savedHunt = f.state.hunt();
            for (int i = 0; i < 45; i++) ManhuntService.tick(f.server);
            var encoded = ManhuntState.CODEC.encodeStart(JsonOps.INSTANCE, f.state).getOrThrow();
            var loaded = ManhuntState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            helper.assertValueEqual(loaded.hunt(), savedHunt, "Save/load retains UUID, name, dimension, and coordinates");
            helper.assertTrue(loaded.isRunner(runner) && !loaded.isRunner(hunter), "Restored UUID identifies runner");
            helper.assertValueEqual(loaded.ticksUntilUpdate(), ManhuntState.UPDATE_INTERVAL_TICKS, "Loaded timer starts a new minute");
            loaded.stop(f.server);
            encoded = ManhuntState.CODEC.encodeStart(JsonOps.INSTANCE, loaded).getOrThrow();
            helper.assertTrue(ManhuntState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().hunt().isEmpty(),
                    "Stopped state also survives save/load");
        }
        helper.succeed();
    }

    @GameTest
    public void startImmediatelyDisablesLocatorBarAndStopRestoresIt(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            var hunter = f.player();
            f.server.getGameRules().set(GameRules.LOCATOR_BAR, true, f.server);
            f.command(runner, "manhunt start @s", true);
            helper.assertFalse(f.server.getGameRules().get(GameRules.LOCATOR_BAR),
                    "Start command immediately disables the locator bar before any further ticks");
            for (var level : f.server.getAllLevels()) {
                helper.assertFalse(level.getGameRules().get(GameRules.LOCATOR_BAR), "Locator bar is off in every dimension");
            }
            helper.assertFalse(ManhuntService.start(f.server, hunter), "A second start is rejected");
            ManhuntService.onServerStarted(f.server);
            ManhuntService.tick(f.server);
            f.command(runner, "manhunt stop", true);
            helper.assertTrue(f.server.getGameRules().get(GameRules.LOCATOR_BAR),
                    "Stop restores the original true setting even after another start attempt or startup hook");
        }
        helper.succeed();
    }

    @GameTest
    public void alreadyDisabledLocatorBarStaysDisabledAfterStop(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            f.server.getGameRules().set(GameRules.LOCATOR_BAR, false, f.server);
            ManhuntService.start(f.server, runner);
            ManhuntService.stop(f.server);
            helper.assertFalse(f.server.getGameRules().get(GameRules.LOCATOR_BAR), "Stop preserves a pre-existing disabled locator bar");
            f.server.getGameRules().set(GameRules.LOCATOR_BAR, true, f.server);
            ManhuntService.onServerStarted(f.server);
            ManhuntService.tick(f.server);
            helper.assertTrue(f.server.getGameRules().get(GameRules.LOCATOR_BAR), "No active hunt leaves the locator-bar setting alone");
        }
        helper.succeed();
    }

    @GameTest
    public void savedHuntRetainsPreviousLocatorBarSetting(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            for (boolean previous : new boolean[]{true, false}) {
                f.server.getGameRules().set(GameRules.LOCATOR_BAR, previous, f.server);
                ManhuntService.start(f.server, runner);
                var encoded = ManhuntState.CODEC.encodeStart(JsonOps.INSTANCE, f.state).getOrThrow();
                var loaded = ManhuntState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
                loaded.disableLocatorBar(f.server);
                helper.assertFalse(f.server.getGameRules().get(GameRules.LOCATOR_BAR), "Loaded hunt keeps the locator bar disabled");
                loaded.stop(f.server);
                helper.assertValueEqual(f.server.getGameRules().get(GameRules.LOCATOR_BAR), previous,
                        "Loaded hunt restores its saved original setting");
                ManhuntService.stop(f.server);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void legacySavedHuntCapturesLocatorBarBeforeDisabling(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            ManhuntService.start(f.server, runner);
            var encoded = ManhuntState.CODEC.encodeStart(JsonOps.INSTANCE, f.state).getOrThrow();
            encoded.getAsJsonObject().remove("locator_bar_before_hunt");
            var loaded = ManhuntState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            f.server.getGameRules().set(GameRules.LOCATOR_BAR, true, f.server);
            loaded.disableLocatorBar(f.server);
            helper.assertFalse(f.server.getGameRules().get(GameRules.LOCATOR_BAR), "A saved hunt from the older mod disables the locator bar");
            loaded.stop(f.server);
            helper.assertTrue(f.server.getGameRules().get(GameRules.LOCATOR_BAR), "Legacy hunt captures and restores the original setting");
        }
        helper.succeed();
    }

    @GameTest
    public void locatorBarRemainsOffThroughoutAnActiveHunt(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            f.server.getGameRules().set(GameRules.LOCATOR_BAR, true, f.server);
            ManhuntService.start(f.server, runner);
            f.server.getGameRules().set(GameRules.LOCATOR_BAR, true, f.server);
            ManhuntService.tick(f.server);
            helper.assertFalse(f.server.getGameRules().get(GameRules.LOCATOR_BAR), "Tick restores the hunt's disabled locator bar");
            ManhuntService.stop(f.server);
            helper.assertTrue(f.server.getGameRules().get(GameRules.LOCATOR_BAR), "Original setting is restored after enforcing the hunt setting");
        }
        helper.succeed();
    }

    @GameTest
    public void fullInventoryDropsCompassWithoutReplacingItems(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            var hunter = f.player();
            ManhuntService.start(f.server, runner);
            var pos = helper.absolutePos(new BlockPos(1, 2, 1));
            hunter.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            hunter.getInventory().clearContent();
            for (int slot = 0; slot < 36; slot++) hunter.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            f.command(hunter, "manhunt compass", false);
            for (int slot = 0; slot < 36; slot++) {
                helper.assertTrue(hunter.getInventory().getItem(slot).is(Items.STONE)
                        && hunter.getInventory().getItem(slot).getCount() == 64, "Inventory slot " + slot + " preserved");
            }
            var drops = hunter.level().getEntitiesOfClass(ItemEntity.class, hunter.getBoundingBox().inflate(3),
                    entity -> TrackingCompass.isTracking(entity.getItem()));
            helper.assertTrue(drops.size() == 1, "Full inventory produces exactly one dropped tracking compass");
            drops.forEach(ItemEntity::discard);
        }
        helper.succeed();
    }

    @GameTest
    public void offhandCompassIsUpdatedAndReused(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            var runner = f.player();
            var hunter = f.player();
            ManhuntService.start(f.server, runner);
            ItemStack tracker = compass(hunter).copy();
            hunter.getInventory().clearContent();
            hunter.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, tracker);
            f.command(hunter, "manhunt compass", false);
            helper.assertValueEqual(compassCount(hunter), 1, "Offhand tracker prevents duplicate replacement");
            runner.snapTo(10, 70, 20);
            for (int i = 0; i < ManhuntState.UPDATE_INTERVAL_TICKS; i++) ManhuntService.tick(f.server);
            helper.assertValueEqual(targetOf(hunter.getOffhandItem()).pos(), new BlockPos(10, 70, 20), "Offhand updates");
        }
        helper.succeed();
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final ManhuntState state;
        final boolean originalLocatorBar;
        final List<ServerPlayer> players = new ArrayList<>();

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            server = helper.getLevel().getServer();
            state = ManhuntState.get(server);
            ManhuntService.stop(server);
            originalLocatorBar = server.getGameRules().get(GameRules.LOCATOR_BAR);
        }

        ServerPlayer player() {
            var player = helper.makeMockServerPlayerInLevel();
            players.add(player);
            return player;
        }

        int command(ServerPlayer player, String command, boolean operator) throws CommandSyntaxException {
            var source = player.createCommandSourceStack().withPermission(
                    operator ? LevelBasedPermissionSet.GAMEMASTER : PermissionSet.NO_PERMISSIONS);
            return server.getCommands().getDispatcher().execute(command, source);
        }

        void expectFailure(ServerPlayer player, String command, boolean operator) throws Exception {
            try {
                command(player, command, operator);
                throw helper.assertionException("Command unexpectedly succeeded: " + command);
            } catch (CommandSyntaxException expected) {
                // Brigadier rejected the action before its game state could change.
            }
        }

        @Override
        public void close() {
            ManhuntService.stop(server);
            server.getGameRules().set(GameRules.LOCATOR_BAR, originalLocatorBar, server);
            for (var player : players) {
                if (server.getPlayerList().getPlayer(player.getUUID()) != null) server.getPlayerList().remove(player);
                player.discard();
            }
        }
    }

    private static boolean hasCompass(ServerPlayer player) {
        return compassCount(player) > 0;
    }

    private static int compassCount(ServerPlayer player) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (TrackingCompass.isTracking(stack)) count += stack.getCount();
        }
        return count;
    }

    private static ItemStack compass(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (TrackingCompass.isTracking(stack)) return stack;
        }
        throw new AssertionError("Player has no tracking compass");
    }

    private static GlobalPos targetOf(ItemStack compass) {
        return Optional.ofNullable(compass.get(DataComponents.LODESTONE_TRACKER))
                .flatMap(tracker -> tracker.target()).orElseThrow();
    }
}
