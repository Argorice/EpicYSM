package com.argorice.epicysm.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.EntityType;

import yesman.epicfight.api.client.event.EpicFightClientEventHooks;
import yesman.epicfight.api.client.event.types.render.PrepareModelEvent;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.client.renderer.patched.entity.PatchedEntityRenderer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * Epic Fight's own event hooks, which it has from 21.15 on. Only loaded
 * where they are there; {@link EpicFightEvents} says which.
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
final class EpicFightHooks {
    private EpicFightHooks() {
    }

    static void onPatchedRenderers(Consumer<EpicFightEvents.PlayerSlot> handler) {
        EpicFightClientEventHooks.Registry.ADD_PATCHED_ENTITY.registerEvent(event -> handler.accept(new EpicFightEvents.PlayerSlot() {
            @Override
            public EntityRendererProvider.Context context() {
                return event.getContext();
            }

            @Override
            public void put(EntityType<?> type, Function<EntityType<?>, PatchedEntityRenderer> provider) {
                event.addPatchedEntityRenderer(type, provider);
            }

            @Override
            public Object event() {
                return event;
            }
        }));
    }

    static void onPlayerModel(Predicate<AbstractClientPlayer> hide) {
        EpicFightClientEventHooks.Render.VALIDATE_PLAYER_MODEL_TO_RENDER.registerEvent(event -> {
            if (event.getPlayerPatch().getOriginal() instanceof AbstractClientPlayer player && hide.test(player)) {
                event.setShouldRender(false);
            }
        });
    }

    static boolean modelCancelled(PatchedEntityRenderer renderer, SkinnedMesh mesh, LivingEntityPatch<?> patch, MultiBufferSource buffers,
                                  PoseStack poseStack, int light, float partialTicks) {
        PrepareModelEvent event = new PrepareModelEvent(renderer, mesh, patch, buffers, poseStack, light, partialTicks);
        return EpicFightClientEventHooks.Render.PREPARE_MODEL_TO_RENDER.post(event).isCanceled();
    }
}
