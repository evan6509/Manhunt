package com.evanchubbuck.manhunt;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Optional;
import java.util.UUID;

/** One hunt per world; only minute snapshots, never live movement, are saved. */
final class ManhuntState extends SavedData {
    static final int UPDATE_INTERVAL_TICKS = 20 * 60;
    static final Codec<ManhuntState> CODEC = Hunt.CODEC.optionalFieldOf("hunt")
            .xmap(ManhuntState::new, state -> state.hunt).codec();
    private static final SavedDataType<ManhuntState> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("manhunt", "state"), ManhuntState::new, CODEC, null);

    private Optional<Hunt> hunt;
    private int ticksUntilUpdate = UPDATE_INTERVAL_TICKS;

    ManhuntState() {
        this(Optional.empty());
    }

    private ManhuntState(Optional<Hunt> hunt) {
        this.hunt = hunt;
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
        ticksUntilUpdate = UPDATE_INTERVAL_TICKS;
        setDirty();
    }

    void stop() {
        hunt = Optional.empty();
        ticksUntilUpdate = UPDATE_INTERVAL_TICKS;
        setDirty();
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
