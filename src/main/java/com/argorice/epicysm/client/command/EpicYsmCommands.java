package com.argorice.epicysm.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import com.argorice.epicysm.client.EpicYsmSettingsScreen;
import com.argorice.epicysm.client.ModelManager;
import com.argorice.epicysm.client.ysm.WeaponBones;

/**
 * Client-side /epicysm command: list the models that were found, reload
 * them from disk, open the settings screen.
 */
public final class EpicYsmCommands {
    private EpicYsmCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("epicysm")
                .then(Commands.literal("list").executes(context -> {
                    var sources = ModelManager.get().sources();

                    if (sources.isEmpty()) {
                        context.getSource().sendSystemMessage(Component.translatable("epicysm.command.no_models"));
                    } else {
                        context.getSource().sendSystemMessage(Component.translatable("epicysm.command.model_list", sources.size()));

                        for (String id : sources.keySet()) {
                            context.getSource().sendSystemMessage(Component.literal("  " + id));
                        }
                    }

                    return sources.size();
                }))
                .then(Commands.literal("reload").executes(context -> {
                    WeaponBones.reload();
                    ModelManager.get().reload();
                    context.getSource().sendSystemMessage(Component.translatable("epicysm.command.reloaded", ModelManager.get().sources().size()));
                    return 1;
                }))
                .then(Commands.literal("config").executes(context -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    // The chat screen closes right after the command; open ours a tick later.
                    minecraft.tell(() -> minecraft.setScreen(new EpicYsmSettingsScreen(null)));
                    return 1;
                }));

        dispatcher.register(root);
    }
}
