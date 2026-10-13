package com.evanchubbuck.manhunt.velocity;

import com.evanchubbuck.manhunt.network.NetworkFiles;
import com.evanchubbuck.manhunt.network.NetworkFiles.Phase;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RotationCoordinatorTest {
    @TempDir Path temporary;
    private static final long NOW = 100_000;

    @Test
    void failedConnectionIsRetriedAndOccupiedSourceNeverResets() throws Exception {
        Fixture fixture = new Fixture();
        Player previous = fixture.addPlayer("a"), hunter = fixture.addPlayer("a");
        fixture.completed("a", previous.getUniqueId(), 2);
        fixture.ready("b", 0);
        RotationCoordinator coordinator = fixture.controller();
        coordinator.tick();
        assertTrue(Files.exists(fixture.file("b", "reservation.json")));
        assertFalse(coordinator.canStartManually());
        assertEquals("b", coordinator.route().orElseThrow().getServerInfo().getName());
        assertFalse(Files.exists(fixture.file("a", "reset.request.json")));

        fixture.complete(previous, true, true);
        fixture.complete(hunter, false, false);
        fixture.completed("a", previous.getUniqueId(), 1);
        fixture.ready("b", 1);
        fixture.newAttempt(hunter);
        coordinator.tick();
        assertFalse(Files.exists(fixture.file("a", "reset.request.json")));
        assertFalse(Files.exists(fixture.file("b", "start.request.json")));

        fixture.complete(hunter, true, true);
        fixture.completed("a", previous.getUniqueId(), 0);
        fixture.ready("b", 2);
        coordinator.tick();
        assertEquals(fixture.worlds.get("a"), NetworkFiles.readReset(fixture.file("a", "reset.request.json"))
                .orElseThrow().worldId());
        var next = NetworkFiles.readStart(fixture.file("b", "start.request.json")).orElseThrow();
        assertEquals(hunter.getUniqueId(), next.runnerId());
        assertEquals(fixture.worlds.get("b"), next.worldId());
    }

    @Test
    void successfulFutureWithoutEmptySourceDoesNotAuthorizeReset() throws Exception {
        Fixture fixture = new Fixture();
        Player runner = fixture.addPlayer("a");
        fixture.completed("a", runner.getUniqueId(), 1);
        fixture.ready("b", 0);
        RotationCoordinator coordinator = fixture.controller();
        coordinator.tick();
        fixture.complete(runner, true, false); // Result alone cannot prove evacuation.
        fixture.completed("a", runner.getUniqueId(), 0); // Nor can an incorrect backend count.
        coordinator.tick();
        assertFalse(Files.exists(fixture.file("a", "reset.request.json")));
        assertFalse(Files.exists(fixture.file("b", "start.request.json")));
    }

    @Test
    void staleDestinationAndChangedGenerationCannotBeUsed() throws Exception {
        Fixture fixture = new Fixture();
        Player runner = fixture.addPlayer("a");
        fixture.completed("a", runner.getUniqueId(), 1);
        fixture.write("b", Phase.READY, null, 0, NOW - 10_000);
        RotationCoordinator coordinator = fixture.controller();
        coordinator.tick();
        assertTrue(coordinator.route().isEmpty());
        assertFalse(Files.exists(fixture.file("b", "reservation.json")));
        fixture.ready("b", 0);
        coordinator.tick();
        fixture.worlds.put("b", UUID.randomUUID());
        fixture.ready("b", 0);
        fixture.complete(runner, true, true);
        fixture.completed("a", runner.getUniqueId(), 0);
        coordinator.tick();
        assertTrue(coordinator.route().isEmpty());
        assertFalse(Files.exists(fixture.file("a", "reset.request.json")));
        assertFalse(Files.exists(fixture.file("b", "start.request.json")));
    }

    @Test
    void proxyRestartPreservesNextRunAndRunnerSelectionUntilBackendAccepts() throws Exception {
        Fixture fixture = new Fixture();
        Player previous = fixture.addPlayer("a"), hunter = fixture.addPlayer("a");
        fixture.completed("a", previous.getUniqueId(), 2);
        fixture.ready("b", 0);
        RotationCoordinator first = fixture.controller();
        first.tick();
        fixture.complete(previous, true, true);
        fixture.complete(hunter, true, true);
        fixture.completed("a", previous.getUniqueId(), 0);
        fixture.ready("b", 2);
        first.tick();
        var request = NetworkFiles.readStart(fixture.file("b", "start.request.json")).orElseThrow();
        Files.delete(fixture.file("b", "start.request.json"));
        RotationCoordinator restored = fixture.controller();
        restored.tick();
        assertEquals(request, NetworkFiles.readStart(fixture.file("b", "start.request.json")).orElseThrow());
        fixture.runs.put("b", request.runId());
        fixture.write("b", Phase.RUNNING, request.runnerId(), 2, NOW);
        restored.tick();
        assertFalse(Files.exists(fixture.file("b", "reservation.json")));
        assertFalse(Files.exists(fixture.file("b", "start.request.json")));
        var persisted = new ControllerStore(fixture.statePath).load();
        assertNull(persisted.transition);
        assertEquals(1, persisted.selections.get(previous.getUniqueId()));
        assertEquals(1, persisted.selections.get(hunter.getUniqueId()));
        assertEquals("b", fixture.controllerRoute().getServerInfo().getName());
    }

    @Test
    void emptyCompletionRotatesButWaitsForTwoPlayersBeforeAutomaticStart() throws Exception {
        Fixture fixture = new Fixture();
        fixture.completed("a", UUID.randomUUID(), 0);
        fixture.ready("b", 0);
        RotationCoordinator coordinator = fixture.controller();
        coordinator.tick();
        coordinator.tick();
        assertTrue(Files.exists(fixture.file("a", "reset.request.json")));
        assertFalse(Files.exists(fixture.file("b", "start.request.json")));
        fixture.addPlayer("b");
        fixture.ready("b", 1);
        coordinator.tick();
        assertFalse(Files.exists(fixture.file("b", "start.request.json")));
        fixture.addPlayer("b");
        fixture.ready("b", 2);
        coordinator.tick();
        assertTrue(Files.exists(fixture.file("b", "start.request.json")));
    }

    @Test
    void rebootWithSameWorldContinuesButDifferentWorldCannotReplaceActiveHunt() throws Exception {
        Fixture fixture = new Fixture();
        UUID runner = UUID.randomUUID();
        fixture.write("a", Phase.RUNNING, runner, 0, NOW);
        fixture.ready("b", 0);
        RotationCoordinator coordinator = fixture.controller();
        coordinator.tick();
        fixture.boots.put("a", UUID.randomUUID());
        fixture.write("a", Phase.RUNNING, runner, 0, NOW);
        coordinator.tick();
        assertEquals("a", coordinator.route().orElseThrow().getServerInfo().getName());
        fixture.worlds.put("a", UUID.randomUUID());
        fixture.write("a", Phase.RUNNING, runner, 0, NOW);
        coordinator.tick();
        assertTrue(coordinator.route().isEmpty());
        assertFalse(Files.exists(fixture.file("a", "reset.request.json")));
    }

    @Test
    void lateStartAcknowledgementAfterRunnerDisconnectAndReselectionSurvivesProxyRestart() throws Exception {
        Fixture fixture = new Fixture();
        Player previous = fixture.addPlayer("a"), firstHunter = fixture.addPlayer("a"), secondHunter = fixture.addPlayer("a");
        fixture.completed("a", previous.getUniqueId(), 3);
        fixture.ready("b", 0);
        RotationCoordinator coordinator = fixture.controller();
        coordinator.tick();
        for (Player player : List.copyOf(fixture.players)) fixture.complete(player, true, true);
        fixture.completed("a", previous.getUniqueId(), 0);
        fixture.ready("b", 3);
        coordinator.tick();
        var originalRequest = NetworkFiles.readStart(fixture.file("b", "start.request.json")).orElseThrow();
        assertNotEquals(previous.getUniqueId(), originalRequest.runnerId());
        Player acceptedRunner = List.of(firstHunter, secondHunter).stream()
                .filter(player -> player.getUniqueId().equals(originalRequest.runnerId())).findFirst().orElseThrow();

        // Fabric accepted the original request but has not published its durable RUNNING heartbeat yet.
        fixture.players.remove(acceptedRunner);
        fixture.members.get("b").remove(acceptedRunner);
        fixture.ready("b", 2);
        coordinator.tick();
        var replacement = NetworkFiles.readStart(fixture.file("b", "start.request.json")).orElseThrow();
        assertEquals(originalRequest.runId(), replacement.runId());
        assertNotEquals(originalRequest.runnerId(), replacement.runnerId());
        assertNotEquals(previous.getUniqueId(), replacement.runnerId());

        // The original acceptance arrives after replacement and a proxy restart.
        fixture.runs.put("b", originalRequest.runId());
        fixture.write("b", Phase.RUNNING, originalRequest.runnerId(), 2, NOW);
        RotationCoordinator restored = fixture.controller();
        restored.tick();
        assertEquals("b", restored.route().orElseThrow().getServerInfo().getName());
        var persisted = new ControllerStore(fixture.statePath).load();
        assertNull(persisted.transition);
        assertEquals(1, persisted.selections.get(originalRequest.runnerId()));
        assertEquals(0, persisted.selections.getOrDefault(replacement.runnerId(), 0));
        assertEquals(originalRequest.runnerId(), persisted.previousRunner);
        restored.tick();
        assertEquals(1, new ControllerStore(fixture.statePath).load().selections.get(originalRequest.runnerId()));
    }

    private final class Fixture {
        final ProxyServer proxy = mock(ProxyServer.class);
        final Map<String, RegisteredServer> servers = new LinkedHashMap<>();
        final Map<String, List<Player>> members = new LinkedHashMap<>();
        final Map<UUID, AtomicReference<String>> playerBackends = new LinkedHashMap<>();
        final Map<UUID, CompletableFuture<ConnectionRequestBuilder.Result>> pending = new LinkedHashMap<>();
        final Map<String, UUID> worlds = new LinkedHashMap<>(), boots = new LinkedHashMap<>(), runs = new LinkedHashMap<>();
        final List<Player> players = new ArrayList<>();
        final Path statePath = temporary.resolve("controller-state.json");
        final PluginConfig config;

        Fixture() {
            var backends = new LinkedHashMap<String, PluginConfig.Backend>();
            for (String name : List.of("a", "b")) {
                worlds.put(name, UUID.randomUUID());
                boots.put(name, UUID.randomUUID());
                runs.put(name, UUID.randomUUID());
                RegisteredServer server = mock(RegisteredServer.class);
                servers.put(name, server);
                members.put(name, new ArrayList<>());
                when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress("localhost", 25565)));
                when(server.getPlayersConnected()).thenAnswer(ignored -> members.get(name));
                when(proxy.getServer(name)).thenReturn(Optional.of(server));
                backends.put(name, new PluginConfig.Backend(name, file(name, "status.json").toString()));
            }
            when(proxy.getAllPlayers()).thenReturn(players);
            config = new PluginConfig(backends, 5, 1);
        }

        Player addPlayer(String backend) {
            Player player = mock(Player.class);
            UUID id = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(id);
            when(player.getUsername()).thenReturn("Player-" + players.size());
            var current = new AtomicReference<>(backend);
            playerBackends.put(id, current);
            ServerConnection connection = mock(ServerConnection.class);
            when(connection.getServerInfo()).thenAnswer(ignored -> servers.get(current.get()).getServerInfo());
            when(player.getCurrentServer()).thenReturn(Optional.of(connection));
            ConnectionRequestBuilder builder = mock(ConnectionRequestBuilder.class);
            when(player.createConnectionRequest(servers.get("b"))).thenReturn(builder);
            when(builder.connect()).thenAnswer(ignored -> pending.get(id));
            players.add(player);
            members.get(backend).add(player);
            newAttempt(player);
            return player;
        }

        void newAttempt(Player player) { pending.put(player.getUniqueId(), new CompletableFuture<>()); }

        void complete(Player player, boolean successful, boolean move) {
            if (move) {
                String source = playerBackends.get(player.getUniqueId()).getAndSet("b");
                members.get(source).remove(player);
                members.get("b").add(player);
            }
            var result = mock(ConnectionRequestBuilder.Result.class);
            when(result.isSuccessful()).thenReturn(successful);
            pending.get(player.getUniqueId()).complete(result);
        }

        Path file(String name, String file) { return temporary.resolve(name).resolve(file); }
        void ready(String name, int count) throws Exception { write(name, Phase.READY, null, count, NOW); }
        void completed(String name, UUID runner, int count) throws Exception { write(name, Phase.COMPLETED, runner, count, NOW); }
        void write(String name, Phase phase, UUID runner, int count, long time) throws Exception {
            NetworkFiles.writeAtomic(file(name, "status.json"), new NetworkFiles.Status(1, name, worlds.get(name), boots.get(name),
                    phase, phase == Phase.READY ? null : runs.get(name), runner,
                    phase == Phase.READY ? null : "Runner", phase == Phase.COMPLETED ? "runner_died" : null, count, time));
        }

        RotationCoordinator controller() throws Exception {
            return new RotationCoordinator(proxy, config, statePath, LoggerFactory.getLogger("test"), () -> NOW, new Random(1));
        }
        RegisteredServer controllerRoute() throws Exception {
            RotationCoordinator coordinator = controller();
            coordinator.tick();
            return coordinator.route().orElseThrow();
        }
    }
}
