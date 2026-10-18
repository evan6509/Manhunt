package com.evanchubbuck.manhunt;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

final class ManhuntCommands {
    private static final SimpleCommandExceptionType ALREADY_RUNNING = error(
            "A manhunt is already running. Use /manhunt stop before starting another.");
    private static final SimpleCommandExceptionType NOT_RUNNING = error("No manhunt is running.");
    private static final SimpleCommandExceptionType RUNNER_COMPASS = error("The runner cannot request a hunting compass.");
    private static final SimpleCommandExceptionType WAITING_FOR_WORLD = error("Waiting for the fresh world and automatic runner selection.");
    private static final SimpleCommandExceptionType WAITING_FOR_LINKED_WORLD = error(
            "Waiting for Linked Hardcore to transfer everyone to a fresh world.");
    private static final SimpleCommandExceptionType START_FAILED = error("Unable to save the new hunt. Check the server log and try again.");

    private ManhuntCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("manhunt")
                .executes(context -> status(context.getSource()))
                .then(Commands.literal("start")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.argument("player", EntityArgument.player()).executes(context -> {
                            if (ManhuntState.get(context.getSource().getServer()).hunt().isPresent()) throw ALREADY_RUNNING.create();
                            if (!ManhuntNetwork.canStart(context.getSource().getServer(), null)) {
                                throw (LinkedHardcoreCompat.waiting(context.getSource().getServer())
                                        ? WAITING_FOR_LINKED_WORLD : WAITING_FOR_WORLD).create();
                            }
                            if (!ManhuntService.start(context.getSource().getServer(), EntityArgument.getPlayer(context, "player"))) {
                                throw START_FAILED.create();
                            }
                            context.getSource().sendSuccess(() -> Component.literal("Manhunt started."), true);
                            return 1;
                        })))
                .then(Commands.literal("stop")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            if (!ManhuntService.stop(context.getSource().getServer())) throw NOT_RUNNING.create();
                            context.getSource().sendSuccess(() -> Component.literal("Manhunt stopped."), true);
                            return 1;
                        }))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("compass").executes(context -> {
                    var source = context.getSource();
                    var player = source.getPlayerOrException();
                    var state = ManhuntState.get(source.getServer());
                    if (state.hunt().isEmpty()) throw NOT_RUNNING.create();
                    if (state.isRunner(player)) throw RUNNER_COMPASS.create();
                    ManhuntService.giveCompass(player);
                    source.sendSuccess(() -> Component.literal(
                            "Your hunting compass is ready. A full inventory drops it at your feet."), false);
                    return 1;
                })));
    }

    private static int status(CommandSourceStack source) throws CommandSyntaxException {
        var state = ManhuntState.get(source.getServer());
        if (state.hunt().isEmpty()) {
            source.sendSuccess(() -> Component.literal(!ManhuntNetwork.canStart(source.getServer(), null)
                    ? LinkedHardcoreCompat.waiting(source.getServer())
                            ? "Waiting for Linked Hardcore to transfer everyone to a fresh world."
                            : "Waiting for the fresh world and automatic runner selection."
                    : "No manhunt is running. Use /manhunt start <player>."), false);
        } else {
            var hunt = state.hunt().orElseThrow();
            boolean online = source.getServer().getPlayerList().getPlayer(hunt.runnerId()) != null;
            source.sendSuccess(() -> Component.literal("Runner: " + hunt.runnerName()
                    + (online ? " (online)" : " (offline; compasses keep the last known location)")
                    + ". Next snapshot in " + ((state.ticksUntilUpdate() + 19) / 20) + " seconds."), false);
        }
        return 1;
    }

    private static SimpleCommandExceptionType error(String message) {
        return new SimpleCommandExceptionType(Component.literal(message));
    }
}
