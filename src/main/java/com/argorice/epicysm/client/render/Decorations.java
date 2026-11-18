package com.argorice.epicysm.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import org.joml.Vector4f;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;

import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.Vec2i;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * What Epic Fight does to a body's colour and light, and what it draws over
 * it - a flash when hit, the glow of a skill - through the decorations of
 * its patch. Epic Fight has them from 20.12 on; this class is only touched
 * where it does, and the body is drawn plain where it does not.
 */
final class Decorations {
    private Decorations() {
    }

    static void color(LivingEntityPatch<?> patch, Vector4f color, float partialTicks) {
        patch.getEntityDecorations().modifyColor(color, partialTicks);
    }

    /** The light the body is drawn with, packed. */
    static int light(LivingEntityPatch<?> patch, int packedLight, float partialTicks) {
        Vec2i light = new Vec2i((packedLight & 0xF0) >> 4, (packedLight & 0xF00000) >> 20);
        patch.getEntityDecorations().modifyLight(light, partialTicks);
        return LightTexture.pack(light.x, light.y);
    }

    static void overlays(LivingEntityPatch<?> patch, HumanoidMesh mesh, PoseStack poseStack, MultiBufferSource buffers, int light,
                         Armature armature, float partialTicks) {
        patch.getEntityDecorations().listDecorationOverlays().forEach(decoration -> {
            if (decoration.shouldRender()) {
                Vector4f color = decoration.color(partialTicks);
                mesh.draw(poseStack, buffers, decoration.getRenderType(), light, color.x(), color.y(), color.z(), color.w(),
                        OverlayTexture.NO_OVERLAY, armature, armature.getPoseMatrices());
            }
        });
    }
}
