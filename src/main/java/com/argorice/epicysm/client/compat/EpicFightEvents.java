package com.argorice.epicysm.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.client.renderer.patched.entity.PatchedEntityRenderer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;

import com.argorice.epicysm.EpicYsm;

/**
 * The three events of Epic Fight this mod listens to or posts: the patched
 * renderers being put together, whether a player's own model is drawn, and
 * a model about to be drawn. From 21.15 on Epic Fight has hooks of its own
 * for them; 21.13 and 21.14 post NeoForge events instead, which are found
 * here by name, so that neither kind is needed for the mod to load.
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public final class EpicFightEvents {
    private static final boolean HOOKS = EpicFightApi.classExists("yesman.epicfight.api.client.event.EpicFightClientEventHooks");
    private static final String OLD = "yesman.epicfight.api.client.neoevent.";

    @Nullable
    private static Constructor<?> oldPrepareModel;
    private static boolean lookedForOldPrepareModel;
    private static boolean saidOld;

    private EpicFightEvents() {
    }

    /** The registration of patched renderers, as far as this mod needs it. */
    public interface PlayerSlot {
        EntityRendererProvider.Context context();

        void put(EntityType<?> type, Function<EntityType<?>, PatchedEntityRenderer> provider);

        /** The event itself, for the renderer registered before this mod's. */
        Object event();
    }

    /** Runs the handler when Epic Fight puts its patched renderers together, after it has put in its own. */
    public static void onPatchedRenderers(IEventBus modBus, Consumer<PlayerSlot> handler) {
        if (HOOKS) {
            EpicFightHooks.onPatchedRenderers(handler);
            return;
        }

        sayOld();

        try {
            Class<? extends Event> type = (Class<? extends Event>) Class.forName(OLD + "PatchedRenderersEvent$Add");
            Method context = type.getMethod("getContext");
            Method add = type.getMethod("addPatchedEntityRenderer", EntityType.class, Function.class);
            modBus.addListener(EventPriority.LOWEST, false, type, event -> handler.accept(new PlayerSlot() {
                @Override
                public EntityRendererProvider.Context context() {
                    try {
                        return (EntityRendererProvider.Context) context.invoke(event);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                }

                @Override
                public void put(EntityType<?> entityType, Function<EntityType<?>, PatchedEntityRenderer> provider) {
                    try {
                        add.invoke(event, entityType, provider);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                }

                @Override
                public Object event() {
                    return event;
                }
            }));
        } catch (Throwable t) {
            EpicYsm.LOGGER.error("Epic Fight: its event for putting patched renderers together could not be found;"
                    + " the player is drawn by Epic Fight's own renderer", t);
        }
    }

    /** Keeps Epic Fight from drawing a player's own model where the test says so. */
    public static void onPlayerModel(Predicate<AbstractClientPlayer> hide) {
        if (HOOKS) {
            EpicFightHooks.onPlayerModel(hide);
            return;
        }

        try {
            Class<? extends Event> type = (Class<? extends Event>) Class.forName(OLD + "RenderEpicFightPlayerEvent");
            Method patch = type.getMethod("getPlayerPatch");
            Method setShouldRender = type.getMethod("setShouldRender", boolean.class);
            NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, type, event -> {
                try {
                    if (((PlayerPatch<?>) patch.invoke(event)).getOriginal() instanceof AbstractClientPlayer player && hide.test(player)) {
                        setShouldRender.invoke(event, false);
                    }
                } catch (ReflectiveOperationException e) {
                    EpicYsm.LOGGER.debug("Could not answer Epic Fight's player model event", e);
                }
            });
        } catch (Throwable t) {
            EpicYsm.LOGGER.error("Epic Fight: its event for drawing a player's own model could not be found", t);
        }
    }

    /** Posts the event that lets other mods change a model about to be drawn; true when one of them called the draw off. */
    public static boolean modelCancelled(PatchedEntityRenderer renderer, SkinnedMesh mesh, LivingEntityPatch<?> patch, MultiBufferSource buffers,
                                         PoseStack poseStack, int light, float partialTicks) {
        if (HOOKS) {
            return EpicFightHooks.modelCancelled(renderer, mesh, patch, buffers, poseStack, light, partialTicks);
        }

        if (!lookedForOldPrepareModel) {
            lookedForOldPrepareModel = true;

            try {
                oldPrepareModel = Class.forName(OLD + "PrepareModelEvent").getConstructor(PatchedEntityRenderer.class, SkinnedMesh.class,
                        LivingEntityPatch.class, MultiBufferSource.class, PoseStack.class, int.class, float.class);
            } catch (Throwable t) {
                EpicYsm.LOGGER.warn("Epic Fight: its event for a model about to be drawn could not be found; models are drawn"
                        + " without it");
            }
        }

        if (oldPrepareModel == null) {
            return false;
        }

        try {
            Event event = (Event) oldPrepareModel.newInstance(renderer, mesh, patch, buffers, poseStack, light, partialTicks);
            NeoForge.EVENT_BUS.post(event);
            return event instanceof ICancellableEvent cancellable && cancellable.isCanceled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void sayOld() {
        if (!saidOld) {
            saidOld = true;
            EpicYsm.LOGGER.info("Epic Fight: this version has no event hooks of its own (before 21.15); its NeoForge events are used instead");
        }
    }
}
