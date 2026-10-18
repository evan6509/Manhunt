package com.evanchubbuck.manhunt;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public final class ManhuntMod implements ModInitializer {
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ManhuntCommands.register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(ManhuntService::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(ManhuntService::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ManhuntNetwork.onServerStopped(server);
            LinkedHardcoreCompat.onServerStopped(server);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register(ManhuntService::afterDeath);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ManhuntService.onPlayerReady(handler.getPlayer()));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            LinkedHardcoreCompat.afterRespawn(newPlayer, alive);
            ManhuntService.onPlayerReady(newPlayer);
        });
    }
}
