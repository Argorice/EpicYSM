package com.argorice.epicysm.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import yesman.epicfight.api.client.model.Mesh;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.EpicFightRenderTypes;

import com.argorice.epicysm.EpicYsm;

/**
 * Draws a converted model's mesh. Epic Fight can skin a mesh on the graphics
 * card ("use compute shader" in its settings), and then no vertex of it goes
 * through the buffers it is drawn into - nor through the recording an
 * afterimage of the body is kept from, which came out empty. On a frame an
 * afterimage keeps, the mesh is skinned the other way Epic Fight has, on
 * the processor, into the same buffers; it looks the same.
 */
final class MeshDraw {
    private static boolean onlyOneWay;

    private MeshDraw() {
    }

    static void draw(SkinnedMesh mesh, boolean kept, PoseStack poseStack, MultiBufferSource buffers, RenderType type, int light,
                     float red, float green, float blue, float alpha, int overlay, Armature armature, OpenMatrix4f[] poses) {
        if (kept && !onlyOneWay) {
            try {
                OnTheProcessor.draw(mesh, poseStack, buffers, type, light, red, green, blue, alpha, overlay, armature, poses);
                return;
            } catch (LinkageError e) {
                onlyOneWay = true;
                EpicYsm.LOGGER.info("Afterimage: this version of Epic Fight cannot be asked to skin a mesh on the processor; with its"
                        + " compute shader on, afterimages of a converted model stay empty ({})", e.toString());
            }
        }

        mesh.draw(poseStack, buffers, type, light, red, green, blue, alpha, overlay, armature, poses);
    }

    /** Only loaded where it is called, so that a version of Epic Fight without it costs the fallback alone. */
    private static final class OnTheProcessor {
        static void draw(SkinnedMesh mesh, PoseStack poseStack, MultiBufferSource buffers, RenderType type, int light,
                         float red, float green, float blue, float alpha, int overlay, Armature armature, OpenMatrix4f[] poses) {
            mesh.drawPosed(poseStack, buffers.getBuffer(EpicFightRenderTypes.getTriangulated(type)), Mesh.DrawingFunction.NEW_ENTITY,
                    light, red, green, blue, alpha, overlay, armature, poses);
        }
    }
}
