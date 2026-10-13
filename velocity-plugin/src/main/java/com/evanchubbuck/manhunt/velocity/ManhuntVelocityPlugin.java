package com.evanchubbuck.manhunt.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Metadata is expanded from the Gradle version in velocity-plugin.json. */
public final class ManhuntVelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private RotationCoordinator coordinator;
    private ScheduledTask poll;

    @Inject
    public ManhuntVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy;
        this.logger = logger;
        this.directory = directory;
    }

    @Subscribe
    public void initialize(ProxyInitializeEvent event) {
        try {
            PluginConfig config = PluginConfig.load(directory.resolve("config.json"));
            for (String backend : config.backendServers().keySet()) {
                if (proxy.getServer(backend).isEmpty()) throw new IOException("Backend is absent from velocity.toml: " + backend);
            }
            coordinator = new RotationCoordinator(proxy, config, directory.resolve("controller-state.json"), logger);
            coordinator.tick();
            poll = proxy.getScheduler().buildTask(this, coordinator::tick)
                    .repeat(config.pollSeconds(), TimeUnit.SECONDS).schedule();
            logger.info("Manhunt world rotation enabled for {}", config.backendServers().keySet());
        } catch (IOException | RuntimeException exception) {
            logger.error("Manhunt could not initialize. Player routing will remain blocked to preserve worlds.", exception);
        }
    }

    @Subscribe
    public void initialServer(PlayerChooseInitialServerEvent event) {
        var destination = coordinator == null ? java.util.Optional.<com.velocitypowered.api.proxy.server.RegisteredServer>empty()
                : coordinator.route();
        if (destination.isPresent()) event.setInitialServer(destination.get());
        else {
            event.setInitialServer(null);
            event.getPlayer().disconnect(Component.text("Manhunt is preparing its next world. Please reconnect shortly."));
        }
    }

    @Subscribe
    public void beforeConnection(ServerPreConnectEvent event) {
        var destination = coordinator == null ? java.util.Optional.<com.velocitypowered.api.proxy.server.RegisteredServer>empty()
                : coordinator.route();
        // Applies to /server, fallback routing, reconnects, and controller transfers equally.
        event.setResult(destination.map(ServerPreConnectEvent.ServerResult::allowed)
                .orElseGet(ServerPreConnectEvent.ServerResult::denied));
    }

    @Subscribe
    public void beforeCommand(CommandExecuteEvent event) {
        String command = event.getCommand().stripLeading().toLowerCase(Locale.ROOT);
        if (command.startsWith("manhunt start") && (coordinator == null || !coordinator.canStartManually())) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            event.getCommandSource().sendMessage(Component.text("The next hunt will start automatically once everyone reaches the fresh world."));
        }
    }

    @Subscribe
    public void shutdown(ProxyShutdownEvent event) {
        if (poll != null) poll.cancel();
    }
}
