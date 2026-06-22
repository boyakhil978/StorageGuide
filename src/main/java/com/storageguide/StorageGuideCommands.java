package com.storageguide;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class StorageGuideCommands {
    private StorageGuideCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("storageguide")
                        .then(Commands.literal("settings")
                                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                                .executes(context -> openSettings(context.getSource())))
                        .then(sloppinessDetector()
                                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS)))
                        .then(Commands.literal("history")
                                .executes(context -> openHistory(context.getSource())))
        ));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> sloppinessDetector() {
        return Commands.literal("sloppiness_detector")
                .executes(context -> {
                    boolean enabled = StorageGuideServer.sloppinessDetectorEnabled();
                    context.getSource().sendSuccess(() -> Component.literal("StorageGuide sloppiness detector is " + (enabled ? "on." : "off.")), false);
                    return enabled ? 1 : 0;
                })
                .then(Commands.literal("on")
                        .executes(context -> setSloppinessDetector(context.getSource(), true)))
                .then(Commands.literal("off")
                        .executes(context -> setSloppinessDetector(context.getSource(), false)));
    }

    private static int setSloppinessDetector(CommandSourceStack source, boolean enabled) {
        StorageGuideServer.setSloppinessDetector(enabled);
        source.sendSuccess(() -> Component.literal("StorageGuide sloppiness detector turned " + (enabled ? "on." : "off.")), true);
        return enabled ? 1 : 0;
    }

    private static int openSettings(CommandSourceStack source) {
        if (!source.isPlayer()) {
            source.sendFailure(Component.literal("StorageGuide settings can only be opened by a player."));
            return 0;
        }

        StorageGuideServer.openOperatorSettings(source.getPlayer());
        return 1;
    }

    private static int openHistory(CommandSourceStack source) {
        if (!source.isPlayer()) {
            source.sendFailure(Component.literal("StorageGuide history can only be opened by a player."));
            return 0;
        }

        StorageGuideServer.sendSloppinessHistory(source.getPlayer());
        return 1;
    }
}
