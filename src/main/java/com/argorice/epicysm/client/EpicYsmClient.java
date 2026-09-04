package com.argorice.epicysm.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import yesman.epicfight.api.client.forgeevent.PatchedRenderersEvent;
import yesman.epicfight.api.client.forgeevent.RenderEpicFightPlayerEvent;

import com.argorice.epicysm.EpicYsm;
import com.argorice.epicysm.client.command.EpicYsmCommands;
import com.argorice.epicysm.client.render.EpicYsmPlayerRenderer;

/** Client bootstrap: renderer replacement, model observation, commands, keys. */
public final class EpicYsmClient {
    /** Opens the settings screen; rebindable under Controls -> EpicYSM. */
    public static final KeyMapping SETTINGS_KEY = new KeyMapping("key.epicysm.settings",
            KeyConflictContext.IN_GAME, KeyModifier.ALT, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O,
            "key.category.epicysm");

    private EpicYsmClient() {
    }

    public static void init(IEventBus modBus) {
        // Settings screen behind the Config button in the mod list.
        try {
            ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new EpicYsmSettingsScreen(parent)));
        } catch (Throwable t) {
            EpicYsm.LOGGER.debug("No settings screen registration on this Forge", t);
        }

        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(SETTINGS_KEY));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }

            while (SETTINGS_KEY.consumeClick()) {
                Minecraft minecraft = Minecraft.getInstance();

                if (minecraft.screen == null) {
                    minecraft.setScreen(new EpicYsmSettingsScreen(null));
                }
            }
        });

        // Epic Fight posts this event after filling in its own renderers,
        // so putting the player entry here replaces the default one.
        modBus.addListener((PatchedRenderersEvent.Add event) -> {
            event.addPatchedEntityRenderer(EntityType.PLAYER,
                    entityType -> new EpicYsmPlayerRenderer(event.getContext(), entityType).initLayerLast(event.getContext(), entityType));
            EpicYsm.LOGGER.info("Replaced the patched player renderer");
        });

        // Watch which model YSM shows for every player, every frame, before
        // Epic Fight can cancel the event. Without this the selection would
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (RenderLivingEvent.Pre<?, ?> event) -> {
            if (!(event.getEntity() instanceof AbstractClientPlayer player)) {
                return;
            }

            ModelManager.get().observeRenderer(player, event.getRenderer());

            // Yes Steve Model's own render is no longer entered. Two
            // things were tried there and both are settled: its pose is not
            if (EpicYsmConfig.skeletonOverlay()
                    && !(event.getRenderer() instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer)
                    && !ModelManager.get().epicFightDraws(player)) {
                com.argorice.epicysm.client.ysm.YsmRenderBridge.intercept(event, player,
                        textureOf(event.getRenderer(), player), ModelManager.get().shouldYieldToYsm(player));
            }
        });

        // A model this mod cannot convert (an encrypted .ysm) would be drawn
        // as a bare vanilla body in battle - the model would simply vanish.
        MinecraftForge.EVENT_BUS.addListener((RenderEpicFightPlayerEvent event) -> {
            if (event.getPlayerPatch().getOriginal() instanceof AbstractClientPlayer player
                    && ModelManager.get().shouldYieldToYsm(player)) {
                event.setShouldRender(false);
            }
        });

        MinecraftForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> EpicYsmCommands.register(event.getDispatcher()));

        // Yes Steve Model warns at start-up that it does not get on with
        // Epic Fight. Getting the two on is what this mod is for, so with
        modBus.addListener(EventPriority.LOWEST, (FMLLoadCompleteEvent event) -> YsmWarning.withdraw());
    }

    /** The texture a foreign renderer is about to draw this player with. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static net.minecraft.resources.ResourceLocation textureOf(Object renderer, AbstractClientPlayer player) {
        try {
            return ((net.minecraft.client.renderer.entity.EntityRenderer) renderer).getTextureLocation(player);
        } catch (Throwable t) {
            return null;
        }
    }
}
