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
        com.argorice.epicysm.client.compat.LookOwners.registerBuiltIn();
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
            // Whatever held the slot before - Epic Fight's own renderer or
            // another mod's - stays reachable behind this one.
            var before = com.argorice.epicysm.client.compat.PlayerRendererSlot.providerBefore(event);
            com.argorice.epicysm.client.compat.PlayerRendererSlot.rememberContext(event.getContext());
            event.addPatchedEntityRenderer(EntityType.PLAYER,
                    entityType -> new EpicYsmPlayerRenderer(event.getContext(), entityType, before).initLayerLast(event.getContext(), entityType));
            EpicYsm.LOGGER.info("Replaced the patched player renderer{}", before != null ? ", keeping the one before it behind" : "");
        });

        // Watch which model YSM shows for every player, every frame, before
        // Epic Fight can cancel the event. Without this the selection would
        // be missed for players whose event never gets past Epic Fight.
        //
        // Two listeners. The first runs before every other mod's and hides
        // the player when a detector of this mod says so. The second runs
        // after every mod that hides players for a moment (a teleport in an
        // attack, a cut in a cinematic) and before Epic Fight: if the event
        // has been cancelled by then, someone hid the player, and Yes Steve
        // Model is not re-rendered through the overlay either - whoever
        // hides a player from Epic Fight hides it from Yes Steve Model too.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (RenderLivingEvent.Pre<?, ?> event) -> {
            if (!(event.getEntity() instanceof AbstractClientPlayer player)) {
                return;
            }

            com.argorice.epicysm.client.compat.PlayerRendererSlot.ensureInFront(player.tickCount);

            if (com.argorice.epicysm.client.compat.LookOwners.hidden(player, event.getRenderer())) {
                event.setCanceled(true);
            }
        });

        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, true, (RenderLivingEvent.Pre<?, ?> event) -> {
            if (!(event.getEntity() instanceof AbstractClientPlayer player)) {
                return;
            }

            if (event.isCanceled()) {
                com.argorice.epicysm.client.compat.LookOwners.hiddenByAnotherMod(player, event.getRenderer());
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

            // Only once the bridge has had its say: the animation itself may
            // have hidden the body this frame, and that is one episode, not
            // a hiding and a showing every frame.
            if (!event.isCanceled()) {
                com.argorice.epicysm.client.compat.LookOwners.shown(player);
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

        // The door every one of Yes Steve Model's drawing paths goes
        // through, where a hidden player is refused. Once every mod is in.
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) ->
                event.enqueueWork(com.argorice.epicysm.client.ysm.YsmRenderGate::install));
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
