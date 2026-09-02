package com.argorice.epicysm.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.common.NeoForge;
import yesman.epicfight.api.client.event.EpicFightClientEventHooks;

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

    public static void init(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        // Settings screen behind the Config button in the mod list.
        try {
            container.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                    (mod, parent) -> new EpicYsmSettingsScreen(parent));
        } catch (Throwable t) {
            EpicYsm.LOGGER.debug("No settings screen registration on this NeoForge", t);
        }

        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(SETTINGS_KEY));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            while (SETTINGS_KEY.consumeClick()) {
                Minecraft minecraft = Minecraft.getInstance();

                if (minecraft.screen == null) {
                    minecraft.setScreen(new EpicYsmSettingsScreen(null));
                }
            }
        });

        // Epic Fight posts this event after filling in its own renderers,
        // so putting the player entry here replaces the default one.
        EpicFightClientEventHooks.Registry.ADD_PATCHED_ENTITY.registerEvent(event -> {
            event.addPatchedEntityRenderer(EntityType.PLAYER,
                    entityType -> new EpicYsmPlayerRenderer(event.getContext(), entityType).initLayerLast(event.getContext(), entityType));
            EpicYsm.LOGGER.info("Replaced the patched player renderer");
        });

        // Watch which model YSM shows for every player, every frame, before
        // Epic Fight can cancel the event. Without this the selection would
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (RenderLivingEvent.Pre<?, ?> event) -> {
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
        EpicFightClientEventHooks.Render.VALIDATE_PLAYER_MODEL_TO_RENDER.registerEvent(event -> {
            if (ModelManager.get().shouldYieldToYsm(event.getPlayerPatch().getOriginal())) {
                event.setShouldRender(false);
            }
        });

        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> EpicYsmCommands.register(event.getDispatcher()));

        // Yes Steve Model warns at start-up that it does not get on with
        // Epic Fight. Getting the two on is what this mod is for, so with
        modBus.addListener(EventPriority.LOWEST,
                (net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent event) -> YsmWarning.withdraw());
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
