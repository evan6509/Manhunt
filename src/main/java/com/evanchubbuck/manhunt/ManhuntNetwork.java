package com.evanchubbuck.manhunt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.evanchubbuck.manhunt.network.NetworkFiles;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/** Optional shared-volume bridge. All game state changes happen on the server thread. */
final class ManhuntNetwork {
    private static final Logger LOGGER = LoggerFactory.getLogger("manhunt-network");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<MinecraftServer, Bridge> BRIDGES = new IdentityHashMap<>();

    private ManhuntNetwork() {}

    record Config(boolean enabled, String serverId, String controlDirectory) {
        Config {
            if (enabled && (serverId == null || !serverId.matches("[a-z][a-z0-9-]{0,31}"))) {
                throw new IllegalArgumentException("Manhunt network serverId must be a lowercase backend ID");
            }
            if (enabled && (controlDirectory == null || Path.of(controlDirectory).isAbsolute()
                    || Path.of(controlDirectory).normalize().startsWith("..") || controlDirectory.isBlank()
                    || Path.of(controlDirectory).normalize().toString().equals("."))) {
                throw new IllegalArgumentException("Manhunt network controlDirectory must stay inside the backend directory");
            }
        }

        static Config standalone() { return new Config(false, "a", "network"); }
    }

    static void onServerStarted(MinecraftServer server) {
        Path file = server.getServerDirectory().resolve("config/manhunt/network.json");
        try {
            Config config;
            if (Files.exists(file)) {
                config = GSON.fromJson(Files.readString(file), Config.class);
                if (config == null) throw new IllegalArgumentException("Empty Manhunt network configuration");
            } else {
                config = Config.standalone();
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(config));
            }
            configure(server, config);
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Unable to configure Manhunt network; check " + file, error);
        }
    }

    static void configure(MinecraftServer server, Config config) {
        Bridge bridge = new Bridge(config, UUID.randomUUID(), server.getServerDirectory().resolve(
                config.enabled() ? config.controlDirectory() : "network"));
        BRIDGES.put(server, bridge);
        if (config.enabled()) {
            bridge.restoreCheckpoint(server);
            ManhuntState.get(server).disableLocatorBar(server);
            bridge.tick(server);
        }
    }

    static Config config(MinecraftServer server) {
        Bridge bridge = BRIDGES.get(server);
        return bridge == null ? Config.standalone() : bridge.config;
    }

    static boolean enabled(MinecraftServer server) { return config(server).enabled(); }

    static boolean canStart(MinecraftServer server, UUID requestedRunId) {
        if (!enabled(server)) return true;
        var state = ManhuntState.get(server);
        if (state.completedRun().isPresent()) return false;
        Bridge bridge = BRIDGES.get(server);
        var reservation = NetworkFiles.readReservation(bridge.directory.resolve("reservation.json"));
        if (reservation.isEmpty() && !Files.notExists(bridge.directory.resolve("reservation.json"))) return false;
        if (reservation.isPresent() && reservation.get().worldId().equals(state.worldId())) {
            return requestedRunId != null && requestedRunId.equals(reservation.get().runId());
        }
        return requestedRunId == null;
    }

    static boolean changed(MinecraftServer server) {
        Bridge bridge = BRIDGES.get(server);
        if (bridge == null || !bridge.config.enabled()) return true;
        if (ManhuntState.get(server).completedRun().isPresent()) freezePlayers(server);
        boolean durable = bridge.writeStatus(server);
        // A failed start must roll back before vanilla saves it. A completion also
        // tries the independent native save, so a single checkpoint IO failure
        // cannot silently resurrect the run after a crash. Status still waits.
        if (durable || ManhuntState.get(server).completedRun().isPresent()) {
            server.overworld().getDataStorage().saveAndJoin();
        }
        return durable;
    }

    static void tick(MinecraftServer server) {
        Bridge bridge = BRIDGES.get(server);
        if (bridge != null && bridge.config.enabled() && server.getTickCount() % 20 == 0) bridge.tick(server);
    }

    static void onPlayerReady(MinecraftServer server) {
        if (enabled(server) && ManhuntState.get(server).completedRun().isPresent()) freezePlayers(server);
    }

    static void onServerStopped(MinecraftServer server) {
        // Leave an acknowledged RESETTING status intact for the controller's crash recovery.
        BRIDGES.remove(server);
    }

    static Path checkpointFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("manhunt-network.json");
    }

    private record DurableState(int schemaVersion, JsonElement state) {}

    private static void freezePlayers(MinecraftServer server) {
        for (var player : server.getPlayerList().getPlayers()) {
            if (!player.isSpectator()) player.setGameMode(GameType.SPECTATOR);
            player.sendOverlayMessage(Component.literal("Hunt finished. Moving everyone to a fresh world..."));
        }
    }

    private static final class Bridge {
        final Config config;
        final UUID bootId;
        final Path directory;
        boolean resetting;

        Bridge(Config config, UUID bootId, Path directory) {
            this.config = config;
            this.bootId = bootId;
            this.directory = directory;
        }

        void restoreCheckpoint(MinecraftServer server) {
            Path file = checkpointFile(server);
            if (Files.notExists(file)) return;
            try {
                if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > 65_536) {
                    throw new IOException("Invalid Manhunt network checkpoint");
                }
                var checkpoint = GSON.fromJson(Files.readString(file), DurableState.class);
                if (checkpoint == null || checkpoint.schemaVersion() != NetworkFiles.SCHEMA || checkpoint.state() == null
                        || !checkpoint.state().isJsonObject() || !checkpoint.state().getAsJsonObject().has("world_id")) {
                    throw new IOException("Invalid Manhunt network checkpoint schema");
                }
                var saved = ManhuntState.CODEC.parse(JsonOps.INSTANCE, checkpoint.state()).getOrThrow();
                var current = ManhuntState.get(server);
                if (current.worldId().equals(saved.worldId())) {
                    if (current.completedRun().isPresent() && !current.completedRun().equals(saved.completedRun())) {
                        throw new IOException("Checkpoint would roll a completed hunt backward");
                    }
                    if (current.hunt().isPresent()) {
                        UUID runId = current.hunt().orElseThrow().runId();
                        boolean sameActive = saved.hunt().map(hunt -> hunt.runId().equals(runId)).orElse(false);
                        boolean sameFinished = saved.completedRun().map(run -> run.runId().equals(runId)).orElse(false);
                        if (!sameActive && !sameFinished) throw new IOException("Checkpoint would forget an active hunt");
                    }
                }
                current.restoreNetworkCheckpoint(saved);
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException("Refusing to treat an unreadable existing world as fresh: " + file, error);
            }
        }

        void tick(MinecraftServer server) {
            var state = ManhuntState.get(server);
            if (state.completedRun().isPresent()) {
                freezePlayers(server);
                var reset = NetworkFiles.readReset(directory.resolve("reset.request.json"));
                resetting = reset.isPresent() && reset.get().worldId().equals(state.worldId())
                        && reset.get().runId().equals(state.completedRun().orElseThrow().runId())
                        && server.getPlayerCount() == 0;
            } else if (state.hunt().isEmpty()) {
                var start = NetworkFiles.readStart(directory.resolve("start.request.json"));
                if (start.isPresent() && start.get().worldId().equals(state.worldId()) && server.getPlayerCount() >= 2
                        && canStart(server, start.get().runId())) {
                    var runner = server.getPlayerList().getPlayer(start.get().runnerId());
                    if (runner != null) ManhuntService.start(server, runner, start.get().runId());
                }
            }
            writeStatus(server);
        }

        boolean writeStatus(MinecraftServer server) {
            var state = ManhuntState.get(server);
            var hunt = state.hunt().orElse(null);
            var finished = state.completedRun().orElse(null);
            var phase = hunt != null ? NetworkFiles.Phase.RUNNING : finished != null
                    ? (resetting ? NetworkFiles.Phase.RESETTING : NetworkFiles.Phase.COMPLETED) : NetworkFiles.Phase.READY;
            try {
                // Minecraft's SavedData writer can swallow IO failures. This fsynced checkpoint is
                // authoritative on reboot, and must succeed before advertising readiness or completion.
                NetworkFiles.writeAtomic(checkpointFile(server), new DurableState(NetworkFiles.SCHEMA,
                        ManhuntState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow()));
            } catch (IOException | RuntimeException error) {
                LOGGER.error("Unable to persist Manhunt network state; transition will wait", error);
                return false;
            }
            try {
                NetworkFiles.writeAtomic(directory.resolve("status.json"), new NetworkFiles.Status(
                        NetworkFiles.SCHEMA, config.serverId(), state.worldId(), bootId, phase,
                        hunt != null ? hunt.runId() : finished != null ? finished.runId() : null,
                        hunt != null ? hunt.runnerId() : finished != null ? finished.runnerId() : null,
                        hunt != null ? hunt.runnerName() : finished != null ? finished.runnerName() : null,
                        finished == null ? null : finished.outcome(), server.getPlayerCount(), System.currentTimeMillis()));
            } catch (IOException | RuntimeException error) {
                LOGGER.error("Unable to publish Manhunt network status; rotation will wait", error);
            }
            return true;
        }
    }
}
