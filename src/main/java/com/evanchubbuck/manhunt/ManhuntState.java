package com.evanchubbuck.manhunt;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Optional;
import java.util.UUID;

/** One hunt per world; only minute snapshots, never live movement, are saved. */
final class ManhuntState extends SavedData {
    static final int UPDATE_INTERVAL_TICKS = 20 * 60;
    static final Codec<ManhuntState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Hunt.CODEC.optionalFieldOf("hunt").forGetter(state -> state.hunt),
            Codec.BOOL.optionalFieldOf("locator_bar_before_hunt").forGetter(state -> state.locatorBarBeforeHunt)
    ).apply(instance, ManhuntState::new));
    private static final SavedDataType<ManhuntState> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("manhunt", "state"), ManhuntState::new, CODEC, null);

    private Optional<Hunt> hunt;
    private Optional<Boolean> locatorBarBeforeHunt;
    private int ticksUntilUpdate = UPDATE_INTERVAL_TICKS;

    ManhuntState() {
        this(Optional.empty(), Optional.empty());
    }

    private ManhuntState(Optional<Hunt> hunt, Optional<Boolean> locatorBarBeforeHunt) {
        this.hunt = hunt;
        this.locatorBarBeforeHunt = hunt.isPresent() ? locatorBarBeforeHunt : Optional.empty();
    }

    static ManhuntState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    Optional<Hunt> hunt() {
        return hunt;
    }

    boolean isRunner(ServerPlayer player) {
        return hunt.map(active -> active.runnerId().equals(player.getUUID())).orElse(false);
    }

    void start(ServerPlayer runner) {
        hunt = Optional.of(new Hunt(runner.getUUID(), runner.getGameProfile().name(), positionOf(runner)));
        locatorBarBeforeHunt = Optional.empty();
        ticksUntilUpdate = UPDATE_INTERVAL_TICKS;
        disableLocatorBar(runner.level().getServer());
        setDirty();
    }

    void stop(MinecraftServer server) {
        locatorBarBeforeHunt.ifPresent(previous -> setLocatorBar(server, previous));
        hunt = Optional.empty();
        locatorBarBeforeHunt = Optional.empty();
        ticksUntilUpdate = UPDATE_INTERVAL_TICKS;
        setDirty();
    }

    /** Capture once, including for saved hunts created before locator-bar control existed. */
    void disableLocatorBar(MinecraftServer server) {
        if (hunt.isEmpty()) return;
        if (locatorBarBeforeHunt.isEmpty()) {
            locatorBarBeforeHunt = Optional.of(server.getGameRules().get(GameRules.LOCATOR_BAR));
            setDirty();
        }
        setLocatorBar(server, false);
    }

    private static void setLocatorBar(MinecraftServer server, boolean enabled) {
        if (server.getGameRules().get(GameRules.LOCATOR_BAR) != enabled) {
            // The vanilla callback immediately removes/rebuilds waypoint connections in every dimension.
            server.getGameRules().set(GameRules.LOCATOR_BAR, enabled, server);
        }
    }

    /** Returns true only at a snapshot boundary. Offline/dead runners keep the old snapshot. */
    boolean tick(ServerPlayer runner) {
        if (hunt.isEmpty()) return false;
        if (--ticksUntilUpdate > 0) return false;
        ticksUntilUpdate = UPDATE_INTERVAL_TICKS;
        if (runner != null && runner.isAlive() && isRunner(runner)) {
            hunt = Optional.of(new Hunt(runner.getUUID(), runner.getGameProfile().name(), positionOf(runner)));
            setDirty();
        }
        return true;
    }

    int ticksUntilUpdate() {
        return ticksUntilUpdate;
    }

    private static GlobalPos positionOf(ServerPlayer runner) {
        return GlobalPos.of(runner.level().dimension(), runner.blockPosition().immutable());
    }

    record Hunt(UUID runnerId, String runnerName, GlobalPos lastKnown) {
        static final Codec<Hunt> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("runner_id").forGetter(Hunt::runnerId),
                Codec.STRING.fieldOf("runner_name").forGetter(Hunt::runnerName),
                GlobalPos.CODEC.fieldOf("last_known").forGetter(Hunt::lastKnown)
        ).apply(instance, Hunt::new));
    }
}
