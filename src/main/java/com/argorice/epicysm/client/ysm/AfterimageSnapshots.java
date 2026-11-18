package com.argorice.epicysm.client.ysm;

import com.mojang.blaze3d.vertex.PoseStack;

import java.lang.reflect.Method;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import yesman.epicfight.api.client.model.Mesh;
import yesman.epicfight.api.utils.EntitySnapshot;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.world.capabilites.entitypatch.player.AbstractClientPlayerPatch;

/**
 * The snapshot Epic Fight makes an afterimage of a player out of, with the
 * picture of the model in place of Epic Fight's own body. Epic Fight takes
 * such snapshots from 20.12 on; this class is only loaded where it does,
 * by the hook into the method that takes them, so the rest of the mod runs
 * on the versions before.
 */
public final class AfterimageSnapshots {
    /** The snapshot's model matrix, which Epic Fight keeps from 20.12.6 on; before that the afterimage is turned by the yaw alone. */
    @Nullable
    private static final Method MODEL_MATRIX = modelMatrixGetter();

    private AfterimageSnapshots() {
    }

    /**
     * An afterimage for this player out of the model on screen, or null for
     * Epic Fight's own. An Object, so that the hook that asks for it names
     * no class of Epic Fight's that an older version lacks.
     */
    @Nullable
    public static Object capture(AbstractClientPlayerPatch<?> patch) {
        try {
            AbstractClientPlayer player = patch.getOriginal();

            if (player == null || !Afterimages.drawsTheModel(player)) {
                return null;
            }

            Ghost ghost = new Ghost(patch);
            Afterimages.Ticket ticket = Afterimages.admit(player.getUUID());

            // Left out: too much of the model is on screen in afterimages
            // already. It stays empty rather than showing Epic Fight's body.
            if (ticket != null) {
                Afterimages.await(player.getUUID(), picture -> {
                    ticket.priced(picture);
                    ghost.ticket = ticket;
                    ghost.picture = picture;
                });
            }

            return ghost;
        } catch (Throwable t) {
            Afterimages.sayFailure(t);
            return null;
        }
    }

    @Nullable
    private static Method modelMatrixGetter() {
        try {
            Method found = EntitySnapshot.class.getMethod("getModelMatrix");
            return found.getReturnType() == OpenMatrix4f.class ? found : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Epic Fight's snapshot of the body, with the picture of the model to
     * draw in place of its own mesh. Everything else - where the afterimage
     * stands, which way it faces, how it fades - is Epic Fight's.
     */
    static final class Ghost extends EntitySnapshot<AbstractClientPlayerPatch<?>> {
        @Nullable
        Afterimages.Picture picture;
        @Nullable
        Afterimages.Ticket ticket;

        /**
         * What Epic Fight's afterimage turns its body by before drawing it:
         * half a turn and the body's own model matrix, or, before Epic Fight
         * kept that matrix, half a turn less the body's yaw. The picture
         * stands as the body stood in the world, and is turned back by this.
         */
        private final Matrix4f undo;

        Ghost(AbstractClientPlayerPatch<?> patch) {
            super(patch);
            Matrix4f turn = null;

            try {
                if (MODEL_MATRIX != null) {
                    turn = new Matrix4f().rotateY((float) Math.PI)
                            .mul(OpenMatrix4f.exportToMojangMatrix((OpenMatrix4f) MODEL_MATRIX.invoke(this)));
                }
            } catch (Throwable t) {
                turn = null;
            }

            if (turn == null) {
                turn = new Matrix4f().rotateY((float) Math.toRadians(180.0F - this.getYRot()));
            }

            this.undo = turn.invert();
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffer, RenderType renderType, Mesh.DrawingFunction drawing,
                           int light, float red, float green, float blue, float alpha) {
            Afterimages.Picture now = this.picture;

            if (now != null) {
                if (this.ticket != null) {
                    this.ticket.drawn();
                }

                now.draw(poseStack, buffer, texture -> renderType, drawing, light, red, green, blue, alpha, this.undo);
            }
        }

        @Override
        public void renderTextured(PoseStack poseStack, MultiBufferSource buffer, Function<ResourceLocation, RenderType> renderTypes,
                                   Mesh.DrawingFunction drawing, int light, float red, float green, float blue, float alpha) {
            Afterimages.Picture now = this.picture;

            if (now != null) {
                if (this.ticket != null) {
                    this.ticket.drawn();
                }

                now.draw(poseStack, buffer, renderTypes, drawing, light, red, green, blue, alpha, this.undo);
            }
        }

        /** The items in the hands were drawn with the body, and are in its picture. */
        @Override
        public void renderItems(PoseStack poseStack, MultiBufferSource buffer, RenderType renderType, Mesh.DrawingFunction drawing,
                                int light, float alpha) {
        }
    }
}
