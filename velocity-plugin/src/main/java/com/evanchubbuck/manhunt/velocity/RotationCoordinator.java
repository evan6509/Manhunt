package com.evanchubbuck.manhunt.velocity;

import com.evanchubbuck.manhunt.network.NetworkFiles;
import com.evanchubbuck.manhunt.network.NetworkFiles.Phase;
import com.evanchubbuck.manhunt.network.NetworkFiles.Status;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/** One durable rotation for all managed players, with no reset until successful evacuation. */
final class RotationCoordinator {
    private final ProxyServer proxy;
    private final PluginConfig config;
    private final Logger logger;
    private final ControllerStore store;
    private final ControllerStore.State state;
    private final LongSupplier clock;
    private final RandomGenerator random;
    private final Map<String, Status> statuses = new LinkedHashMap<>();
    private boolean connecting;
    private boolean halted;
    private long lastNotice;

    RotationCoordinator(ProxyServer proxy, PluginConfig config, Path statePath, Logger logger) throws IOException {
        this(proxy, config, statePath, logger, System::currentTimeMillis, RandomGenerator.getDefault());
    }

    RotationCoordinator(ProxyServer proxy, PluginConfig config, Path statePath, Logger logger,
                        LongSupplier clock, RandomGenerator random) throws IOException {
        this.proxy = proxy;
        this.config = config;
        this.logger = logger;
        this.clock = clock;
        this.random = random;
        this.store = new ControllerStore(statePath);
        this.state = store.load();
        if (state.activeBackend != null && !config.backendServers().containsKey(state.activeBackend)) {
            throw new IOException("Persisted active backend is no longer configured");
        }
        if (state.transition != null && (!config.backendServers().containsKey(state.transition.sourceBackend)
                || (state.transition.destinationBackend != null
                    && !config.backendServers().containsKey(state.transition.destinationBackend)))) {
            throw new IOException("Persisted rotation references a removed backend");
        }
    }

    synchronized void tick() {
        if (halted) return;
        try {
            refreshStatuses();
            establishActiveWorld();
            acknowledgeAutomaticStart();
            detectCompletion();
            advanceRotation();
            requestSafeResets();
        } catch (IOException | RuntimeException exception) {
            halt(exception);
        }
    }

    private void refreshStatuses() {
        statuses.clear();
        config.backendServers().forEach((name, backend) -> NetworkFiles.readStatus(backend.statusPath())
                .filter(status -> backend.serverId().equals(status.serverId()))
                .filter(status -> NetworkFiles.isFresh(status, clock.getAsLong(), config.staleSeconds()))
                .ifPresent(status -> statuses.put(name, status)));
    }

    private void establishActiveWorld() throws IOException {
        if (state.activeBackend != null) return;
        List<String> existingRuns = statuses.entrySet().stream()
                .filter(entry -> entry.getValue().phase() == Phase.RUNNING || entry.getValue().phase() == Phase.COMPLETED)
                .map(Map.Entry::getKey).toList();
        if (existingRuns.size() > 1) throw new IOException("Multiple existing hunts; refusing to choose one to erase");
        String initial = existingRuns.isEmpty() ? statuses.entrySet().stream()
                .filter(entry -> entry.getValue().phase() == Phase.READY && !isRetired(entry.getKey(), entry.getValue().worldId()))
                .map(Map.Entry::getKey).findFirst().orElse(null) : existingRuns.getFirst();
        if (initial != null) {
            state.activeBackend = initial;
            state.activeWorldId = statuses.get(initial).worldId();
            store.save(state);
        }
    }

    private void acknowledgeAutomaticStart() throws IOException {
        var transition = state.transition;
        if (transition == null || !transition.moved) return;
        Status destination = current(transition.destinationBackend, transition.destinationWorldId);
        if (destination == null || (destination.phase() != Phase.RUNNING && destination.phase() != Phase.COMPLETED)) return;
        if (!transition.nextRunId.equals(destination.runId())
                || (!java.util.Objects.equals(transition.selectedRunner, destination.runnerId())
                    && !transition.authorizedRunners.contains(destination.runnerId()))) {
            throw new IOException("Destination started an unexpected hunt while rotation was reserved");
        }
        countRun(destination);
        state.transition = null;
        store.save(state);
        Path control = config.backendServers().get(transition.destinationBackend).statusPath().getParent();
        NetworkFiles.readReservation(control.resolve("reservation.json"))
                .filter(reservation -> reservation.worldId().equals(transition.destinationWorldId)
                        && reservation.runId().equals(transition.nextRunId))
                .ifPresent(reservation -> deleteMailbox(control.resolve("reservation.json")));
        NetworkFiles.readStart(control.resolve("start.request.json"))
                .filter(request -> request.worldId().equals(transition.destinationWorldId)
                        && request.runId().equals(transition.nextRunId))
                .ifPresent(request -> deleteMailbox(control.resolve("start.request.json")));
        logger.info("Manhunt {} started on {} with runner {}", destination.runId(),
                state.activeBackend, destination.runnerName());
    }

    private void detectCompletion() throws IOException {
        if (state.transition != null || state.activeBackend == null) return;
        Status active = current(state.activeBackend, state.activeWorldId);
        if (active == null) return;
        if (active.phase() == Phase.RUNNING) {
            if (!active.runId().equals(state.lastCountedRunId)) {
                countRun(active);
                store.save(state);
            }
        } else if (active.phase() == Phase.COMPLETED) {
            // A very short manual hunt may finish between two polls; count its runner once too.
            countRun(active);
            var transition = new ControllerStore.Transition();
            transition.sourceBackend = state.activeBackend;
            transition.sourceWorldId = active.worldId();
            transition.sourceRunId = active.runId();
            transition.previousRunner = active.runnerId();
            transition.nextRunId = UUID.randomUUID();
            state.transition = transition;
            state.retired.put(state.activeBackend, new ControllerStore.RetiredWorld(active.worldId(), active.runId()));
            store.save(state);
            logger.info("Manhunt {} completed ({}); reserving a fresh world", active.runId(), active.outcome());
        }
    }

    private void countRun(Status status) {
        if (!status.runId().equals(state.lastCountedRunId)) {
            state.selections.merge(status.runnerId(), 1, Math::addExact);
            state.lastCountedRunId = status.runId();
        }
        state.previousRunner = status.runnerId();
    }

    private void advanceRotation() throws IOException {
        var transition = state.transition;
        if (transition == null) return;
        if (transition.destinationBackend == null) {
            for (var entry : config.backendServers().entrySet()) {
                String name = entry.getKey();
                Status candidate = statuses.get(name);
                if (!name.equals(transition.sourceBackend) && candidate != null && candidate.phase() == Phase.READY
                        && candidate.playerCount() == 0 && !isRetired(name, candidate.worldId()) && serverEmpty(name)) {
                    transition.destinationBackend = name;
                    transition.destinationWorldId = candidate.worldId();
                    store.save(state); // The reservation survives a proxy restart before any player moves.
                    break;
                }
            }
        }
        if (transition.destinationBackend == null) {
            notice("Hunt finished. Waiting for a fresh Manhunt world.");
            return;
        }
        Status destination = current(transition.destinationBackend, transition.destinationWorldId);
        if (destination == null) {
            notice("Waiting for the next Manhunt server to become available.");
            return;
        }
        // Also recreate the reservation after a proxy restart or interrupted mailbox write.
        if (destination.phase() == Phase.READY) {
            Path reservationPath = config.backendServers().get(transition.destinationBackend).controlFile("reservation.json");
            var expected = new NetworkFiles.Reservation(NetworkFiles.SCHEMA, transition.destinationWorldId, transition.nextRunId);
            if (NetworkFiles.readReservation(reservationPath).filter(expected::equals).isEmpty()) {
                NetworkFiles.writeAtomic(reservationPath, expected);
            }
        }
        if (!transition.moved) {
            if (destination.phase() != Phase.READY) throw new IOException("Reserved destination is no longer a fresh world");
            if (!connecting) beginConnections(transition);
            return;
        }
        if (destination.phase() != Phase.READY) return;
        List<Player> arrived = proxy.getServer(transition.destinationBackend)
                .map(server -> List.copyOf(server.getPlayersConnected())).orElse(List.of());
        if (arrived.size() < 2) {
            notice("Fresh world ready. Waiting for at least two players to start the next hunt.");
            return;
        }
        List<UUID> ids = arrived.stream().map(Player::getUniqueId).toList();
        if (transition.selectedRunner == null || !ids.contains(transition.selectedRunner)) {
            // A previous request may already be accepted while its RUNNING heartbeat is still being flushed.
            // Keep its authorization even when an offline candidate must be replaced, including across restarts.
            if (transition.selectedRunner != null) transition.authorizedRunners.add(transition.selectedRunner);
            transition.selectedRunner = RunnerSelection.choose(ids, transition.previousRunner, state.selections, random)
                    .orElseThrow();
            transition.authorizedRunners.add(transition.selectedRunner);
            store.save(state); // Persist the exact request before Fabric can accept it.
        }
        NetworkFiles.writeAtomic(config.backendServers().get(transition.destinationBackend).controlFile("start.request.json"),
                new NetworkFiles.StartRequest(NetworkFiles.SCHEMA, transition.destinationWorldId,
                        transition.nextRunId, transition.selectedRunner));
    }

    private void beginConnections(ControllerStore.Transition transition) {
        Optional<RegisteredServer> registered = proxy.getServer(transition.destinationBackend);
        if (registered.isEmpty()) return;
        connecting = true;
        notice("Hunt finished. Moving everyone to a fresh world.");
        List<CompletableFuture<Boolean>> outcomes = new ArrayList<>();
        for (Player player : List.copyOf(proxy.getAllPlayers())) {
            String backend = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null);
            if (backend == null || !config.backendServers().containsKey(backend)
                    || transition.destinationBackend.equals(backend)) continue;
            try {
                outcomes.add(player.createConnectionRequest(registered.get()).connect().handle((result, error) -> {
                    boolean successful = error == null && result != null && result.isSuccessful();
                    if (!successful) logger.warn("Could not move {} to {}; retaining the source world", player.getUsername(),
                            transition.destinationBackend, error);
                    return successful;
                }));
            } catch (RuntimeException exception) {
                logger.warn("Could not request transfer for {}; retaining the source world", player.getUsername(), exception);
                outcomes.add(CompletableFuture.completedFuture(false));
            }
        }
        CompletableFuture.allOf(outcomes.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            boolean success = error == null && outcomes.stream().allMatch(result -> Boolean.TRUE.equals(result.getNow(false)));
            finishConnections(transition, success);
        });
    }

    private synchronized void finishConnections(ControllerStore.Transition transition, boolean success) {
        connecting = false;
        if (halted || state.transition != transition || transition.moved) return;
        // New joins are routed to the reserved destination, so this emptiness test remains meaningful.
        boolean sourcesEmpty = config.backendServers().keySet().stream()
                .filter(name -> !name.equals(transition.destinationBackend)).allMatch(this::serverEmpty);
        boolean destinationUnchanged = NetworkFiles.readStatus(config.backendServers().get(transition.destinationBackend).statusPath())
                .filter(status -> status.worldId().equals(transition.destinationWorldId) && status.phase() == Phase.READY
                        && NetworkFiles.isFresh(status, clock.getAsLong(), config.staleSeconds())).isPresent();
        if (!success || !sourcesEmpty || !destinationUnchanged) {
            logger.warn("Rotation incomplete (connectionsSucceeded={}, sourcesEmpty={}, destinationUnchanged={}); retrying without resetting",
                    success, sourcesEmpty, destinationUnchanged);
            return;
        }
        transition.moved = true;
        state.activeBackend = transition.destinationBackend;
        state.activeWorldId = transition.destinationWorldId;
        try {
            store.save(state); // Durable active world and next run precede every reset request.
        } catch (IOException | RuntimeException exception) {
            halt(exception);
        }
    }

    private void requestSafeResets() throws IOException {
        boolean changed = false;
        for (var entry : new ArrayList<>(state.retired.entrySet())) {
            String name = entry.getKey();
            var retired = entry.getValue();
            Status status = statuses.get(name);
            if (status == null) continue;
            if (!retired.worldId().equals(status.worldId())) {
                state.retired.remove(name);
                changed = true;
                continue;
            }
            var transition = state.transition;
            if (transition != null && !transition.moved && name.equals(transition.sourceBackend)) continue;
            if (name.equals(state.activeBackend) || !serverEmpty(name) || status.playerCount() != 0
                    || !retired.runId().equals(status.runId())
                    || (status.phase() != Phase.COMPLETED && status.phase() != Phase.RESETTING)) continue;
            Path requestPath = config.backendServers().get(name).controlFile("reset.request.json");
            var expected = new NetworkFiles.ResetRequest(NetworkFiles.SCHEMA, retired.worldId(), retired.runId());
            if (!NetworkFiles.readReset(requestPath).filter(expected::equals).isPresent()) {
                NetworkFiles.writeAtomic(requestPath, expected);
                logger.info("Requested reset of evacuated world {} on {}", retired.worldId(), name);
            }
        }
        if (changed) store.save(state);
    }

    synchronized Optional<RegisteredServer> route() {
        if (halted) return Optional.empty();
        var transition = state.transition;
        String name = transition == null ? state.activeBackend : transition.destinationBackend;
        UUID worldId = transition == null ? state.activeWorldId : transition.destinationWorldId;
        if (name == null || worldId == null) return Optional.empty();
        Status status = current(name, worldId);
        if (status == null || isRetired(name, worldId)
                || (status.phase() != Phase.READY && status.phase() != Phase.RUNNING)) return Optional.empty();
        return proxy.getServer(name);
    }

    synchronized boolean canStartManually() {
        if (halted || state.transition != null) return false;
        Status status = current(state.activeBackend, state.activeWorldId);
        return status != null && status.phase() == Phase.READY;
    }

    private Status current(String name, UUID worldId) {
        Status status = statuses.get(name);
        return status != null && status.worldId().equals(worldId)
                && NetworkFiles.isFresh(status, clock.getAsLong(), config.staleSeconds()) ? status : null;
    }

    private boolean isRetired(String name, UUID worldId) {
        return Optional.ofNullable(state.retired.get(name)).map(retired -> retired.worldId().equals(worldId)).orElse(false);
    }

    private boolean serverEmpty(String name) {
        // Missing configured server registrations are errors, not proof of evacuation.
        return proxy.getServer(name).map(server -> server.getPlayersConnected().isEmpty()).orElse(false);
    }

    private void notice(String text) {
        long now = clock.getAsLong();
        if (now - lastNotice < 5_000) return;
        lastNotice = now;
        for (Player player : proxy.getAllPlayers()) {
            if (player.getCurrentServer().map(connection -> config.backendServers().containsKey(connection.getServerInfo().getName()))
                    .orElse(false)) player.sendActionBar(Component.text(text));
        }
    }

    private void halt(Exception exception) {
        halted = true;
        logger.error("Manhunt routing paused to preserve worlds. Repair the reported problem and restart the proxy.", exception);
    }

    private void deleteMailbox(Path path) {
        try {
            java.nio.file.Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
