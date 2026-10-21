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

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** One hunt per world; only minute snapshots, never live movement, are saved. */
final class ManhuntState extends SavedData {
    static final int UPDATE_INTERVAL_TICKS = 20 * 60;
    static final Codec<ManhuntState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Hunt.CODEC.optionalFieldOf("hunt").forGetter(state -> state.hunt),
            Codec.BOOL.optionalFieldOf("locator_bar_before_hunt").forGetter(state -> state.locatorBarBeforeHunt),
            UUIDUtil.CODEC.optionalFieldOf("world_id").forGetter(state -> Optional.of(state.worldId)),
            Completion.CODEC.optionalFieldOf("completed_run").forGetter(state -> state.completedRun),
            Codec.BOOL.optionalFieldOf("linked_transfer_pending", false).forGetter(state -> state.linkedTransferPending),
            UUIDUtil.CODEC.listOf().optionalFieldOf("excluded_dragon_defeats", List.of())
                    .forGetter(state -> List.copyOf(state.excludedDragonDefeats))
    ).apply(instance, ManhuntState::new));
    private static final SavedDataType<ManhuntState> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("manhunt", "state"), ManhuntState::new, CODEC, null);

    private Optional<Hunt> hunt;
    private Optional<Boolean> locatorBarBeforeHunt;
    private UUID worldId;
    private Optional<Completion> completedRun;
    private boolean linkedTransferPending;
    private boolean identityNeedsSave;
    // Reading a new/legacy state marks it dirty, but its generated UUID is still provisional.
    private boolean identityEstablished;
    private Set<UUID> excludedDragonDefeats;
    private int ticksUntilUpdate = UPDATE_INTERVAL_TICKS;

    ManhuntState() {
        this(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, List.of());
    }

    private ManhuntState(Optional<Hunt> hunt, Optional<Boolean> locatorBarBeforeHunt,
                         Optional<UUID> worldId, Optional<Completion> completedRun, boolean linkedTransferPending,
                         List<UUID> excludedDragonDefeats) {
        this.hunt = hunt;
        this.locatorBarBeforeHunt = hunt.isPresent() ? locatorBarBeforeHunt : Optional.empty();
        this.worldId = worldId.orElseGet(UUID::randomUUID);
        this.completedRun = hunt.isEmpty() ? completedRun : Optional.empty();
        this.linkedTransferPending = linkedTransferPending;
        identityNeedsSave = worldId.isEmpty();
        identityEstablished = worldId.isPresent();
        this.excludedDragonDefeats = new HashSet<>(excludedDragonDefeats);
    }

    static ManhuntState get(MinecraftServer server) {
        var state = server.overworld().getDataStorage().computeIfAbsent(TYPE);
        if (state.identityNeedsSave) {
            state.setDirty();
            state.identityNeedsSave = false;
        }
        return state;
    }

    Optional<Hunt> hunt() {
        return hunt;
    }

    UUID worldId() { return worldId; }

    boolean hasEstablishedWorldIdentity() { return identityEstablished; }

    void confirmWorldIdentity() { identityEstablished = true; }

    void excludeDragonDefeat(UUID dragonId) {
        if (excludedDragonDefeats.add(dragonId)) setDirty();
    }

    boolean isDragonDefeatExcluded(UUID dragonId) { return excludedDragonDefeats.contains(dragonId); }

    Optional<Completion> completedRun() { return completedRun; }

    boolean linkedTransferPending() { return linkedTransferPending; }

    void awaitLinkedTransfer() {
        linkedTransferPending = true;
        setDirty();
    }

    void restoreNetworkCheckpoint(ManhuntState saved) {
        worldId = saved.worldId;
        hunt = saved.hunt;
        completedRun = saved.completedRun;
        locatorBarBeforeHunt = saved.locatorBarBeforeHunt;
        linkedTransferPending = saved.linkedTransferPending;
        ticksUntilUpdate = saved.ticksUntilUpdate;
        identityNeedsSave = false;
        identityEstablished = saved.hasEstablishedWorldIdentity();
        excludedDragonDefeats = new HashSet<>(saved.excludedDragonDefeats);
        setDirty();
    }

    boolean isRunner(ServerPlayer player) {
        return hunt.map(active -> active.runnerId().equals(player.getUUID())).orElse(false);
    }

    void start(ServerPlayer runner) {
        start(runner, UUID.randomUUID());
    }

    void start(ServerPlayer runner, UUID runId) {
        excludedDragonDefeats.clear();
        hunt = Optional.of(new Hunt(runner.getUUID(), runner.getGameProfile().name(), positionOf(runner), runId));
        completedRun = Optional.empty();
        locatorBarBeforeHunt = Optional.empty();
        ticksUntilUpdate = UPDATE_INTERVAL_TICKS;
        disableLocatorBar(runner.level().getServer());
        setDirty();
    }

    void stop(MinecraftServer server) {
        excludedDragonDefeats.clear();
        locatorBarBeforeHunt.ifPresent(previous -> setLocatorBar(server, previous));
        hunt = Optional.empty();
        completedRun = Optional.empty();
        locatorBarBeforeHunt = Optional.empty();
        ticksUntilUpdate = UPDATE_INTERVAL_TICKS;
        setDirty();
    }

    void complete(MinecraftServer server, String outcome) {
        Hunt finished = hunt.orElseThrow();
        stop(server);
        completedRun = Optional.of(new Completion(finished.runId(), finished.runnerId(), finished.runnerName(), outcome));
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
            hunt = Optional.of(new Hunt(runner.getUUID(), runner.getGameProfile().name(), positionOf(runner), hunt.orElseThrow().runId()));
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

    record Hunt(UUID runnerId, String runnerName, GlobalPos lastKnown, UUID runId) {
        static final Codec<Hunt> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("runner_id").forGetter(Hunt::runnerId),
                Codec.STRING.fieldOf("runner_name").forGetter(Hunt::runnerName),
                GlobalPos.CODEC.fieldOf("last_known").forGetter(Hunt::lastKnown),
                UUIDUtil.CODEC.optionalFieldOf("run_id").forGetter(hunt -> Optional.of(hunt.runId()))
        ).apply(instance, (runnerId, name, position, runId) -> new Hunt(runnerId, name, position, runId.orElseGet(UUID::randomUUID))));
    }

    record Completion(UUID runId, UUID runnerId, String runnerName, String outcome) {
        static final Codec<Completion> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("run_id").forGetter(Completion::runId),
                UUIDUtil.CODEC.fieldOf("runner_id").forGetter(Completion::runnerId),
                Codec.STRING.fieldOf("runner_name").forGetter(Completion::runnerName),
                Codec.STRING.fieldOf("outcome").forGetter(Completion::outcome)
        ).apply(instance, Completion::new));
    }
}
