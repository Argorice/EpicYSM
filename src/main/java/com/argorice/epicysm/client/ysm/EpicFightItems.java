package com.argorice.epicysm.client.ysm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.MathUtils;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.QuaternionUtils;
import yesman.epicfight.client.events.engine.RenderEngine;
import yesman.epicfight.client.renderer.patched.item.RenderItemBase;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import com.argorice.epicysm.EpicYsm;

/**
 * Draws the held item on an encrypted model the way Epic Fight draws it on an
 * open one, in the space the body was actually drawn in.
 */
public final class EpicFightItems {
    private EpicFightItems() {
    }

    /** Whether this mod draws the held item. */
    private static boolean own = true;

    public static boolean own() {
        return own;
    }

    public static void setOwn(boolean value) {
        own = value;
    }

    /** Renderer classes that threw, each named once and left alone after. */
    private static final Set<String> brokenRenderers = new HashSet<>();
    private static boolean modelMatrixFallback;

    /**
     * Draws one hand's item. The pose stack arrives as the render event
     * handed it over and leaves exactly as it came.
     */
    public static boolean drawAt(AbstractClientPlayer player, PoseStack poseStack, MultiBufferSource buffers,
                                 int light, float partialTicks, InteractionHand hand,
                                 List<Matrix4f> captured) {
        if (!own) {
            return false;
        }

        LivingEntityPatch<?> patch;
        ItemStack stack;
        RenderItemBase renderer;

        try {
            patch = EpicFightCapabilities.getEntityPatch(player, LivingEntityPatch.class);

            if (patch == null) {
                return false;
            }

            boolean off = hand == InteractionHand.OFF_HAND;
            stack = off ? player.getOffhandItem() : player.getMainHandItem();

            if (stack.isEmpty()) {
                return false;
            }

            boolean valid = off ? patch.isOffhandItemValid() : patch.isMainhandItemValid();

            if (!valid) {
                if (off && !saidOffhand) {
                    saidOffhand = true;
                    com.argorice.epicysm.client.Diag.info("Offhand: Epic Fight says the item in the off hand ({}) is not to be drawn"
                            + " while {} is in the main hand - the same rule it applies to its own models, a"
                            + " two-handed weapon hides the off hand. Yes Steve Model's copy is put away too.",
                            stack.getItem(), player.getMainHandItem().getItem());
                }

                return false;
            }

            renderer = RenderEngine.getInstance().getItemRenderer(stack);

            if (off && !saidOffhand) {
                saidOffhand = true;
                com.argorice.epicysm.client.Diag.info("Offhand: drawing {} with {}", stack.getItem(),
                        renderer == null ? "no renderer" : renderer.getClass().getName());
            }
        } catch (Throwable t) {
            complain("Epic Fight's item lookup", t);
            return false;
        }

        if (renderer == null || brokenRenderers.contains(renderer.getClass().getName())) {
            return false;
        }

        Map<String, Matrix4f> drawn = YsmSkeletonOverlay.of(player).drawnJoints(player);

        if (drawn.isEmpty()) {
            return false;
        }

        // The joints in the open models' units. An open model is built at
        // its size and then drawn through Epic Fight's model matrix, which
        float ruler = YsmPoseSolver.modelScale();
        float drawnAt = ruler > 0.0F ? ruler : 0.7F;
        float body = (player.isBaby() ? 0.5F : 1.0F) * 0.9375F;
        float scale = drawnAt / body;
        OpenMatrix4f[] joints = blocks(patch.getArmature(), drawn, scale);

        if (joints == null) {
            return false;
        }

        boolean off = hand == InteractionHand.OFF_HAND;
        poseStack.pushPose();

        try {
            poseStack.mulPose(QuaternionUtils.YP.rotationDegrees(180.0F));
            MathUtils.mulStack(poseStack, bodySpace(player, patch, partialTicks));

            // The last few centimetres. The solve places the joints from the
            // model's own pivots, and Yes Steve Model draws the body from the
            if (!off && YsmPoseSolver.worldFrame()) {
                Matrix4f tool = drawn.get("Tool_R");
                float nearest = Float.MAX_VALUE;

                if (tool != null) {
                    // The tool joint itself, with no renderer's correction on
                    // it, against the hand locator as Yes Steve Model drew it
                    Vector3f mine = new Matrix4f(poseStack.last().pose())
                            .transformPosition(tool.getTranslation(new Vector3f()).mul(scale));

                    for (Matrix4f space : captured) {
                        Vector3f theirs = locatorPoint(player, stack, false, space);

                        if (theirs != null && mine.isFinite()) {
                            nearest = Math.min(nearest, theirs.distance(mine));
                        }
                    }
                }

                watch(nearest == Float.MAX_VALUE ? Float.NaN : nearest, scale, renderer.getClass().getName());
            }

            // With this model's own skeleton standing in for the patch's.
            //
            Armature stand = standIn(patch, joints, partialTicks);
            Object held = stand == null ? null : swapArmature(patch, stand);

            try {
                renderer.renderItemInHand(stack, patch, hand, joints, buffers, poseStack, light, partialTicks);
            } finally {
                if (held != null) {
                    swapArmature(patch, (Armature) held);
                }
            }

            return true;
        } catch (Throwable t) {
            // One renderer misbehaving - an add-on built against another
            // Epic Fight, say - must not take the item, the model or the
            // game with it. It is named once and not asked again.
            String name = renderer.getClass().getName();

            if (brokenRenderers.add(name)) {
                EpicYsm.LOGGER.warn("The held item's renderer {} does not work with this mod's Epic Fight or"
                        + " Yes Steve Model; items it draws are left to Yes Steve Model from now on. Nothing"
                        + " else is affected.", name, t);
            }

            return false;
        } finally {
            poseStack.popPose();
        }
    }

    /** Epic Fight's own player model matrix, built with the body's own yaw. */
    private static OpenMatrix4f bodySpace(AbstractClientPlayer player, LivingEntityPatch<?> patch,
                                         float partialTicks) {
        if (!modelMatrixFallback) {
            try {
                float size = (player.isBaby() ? 0.5F : 1.0F) * 0.9375F * entityScale(player);
                Entity vehicle = player.getVehicle();
                float yawO;
                float yaw;

                if (vehicle instanceof LivingEntity ridden) {
                    yawO = ridden.yBodyRotO;
                    yaw = ridden.yBodyRot;
                } else {
                    yawO = player.yBodyRotO;
                    yaw = player.yBodyRot;
                }

                return MathUtils.getModelMatrixIntegral(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
                        yawO, yaw, partialTicks, size, size, size);
            } catch (Throwable t) {
                modelMatrixFallback = true;
                EpicYsm.LOGGER.warn("This Epic Fight does not build its model matrix the way this mod expects;"
                        + " its own is used instead, so a weapon may twist against the hand for a moment"
                        + " when an attack turns the body.", t);
            }
        }

        return patch.getModelMatrix(partialTicks);
    }

    /* ------------------------------------------------------------------
     * A stand-in armature for the add-ons that pose their own
     * ------------------------------------------------------------------ */

    /** The patch's armature with this model's joints for its pose. */
    private static final class HandedArmature extends yesman.epicfight.model.armature.HumanoidArmature {
        @Nullable
        OpenMatrix4f[] override;

        HandedArmature(String name, int jointNumber, Joint rootJoint, Map<String, Joint> jointMap) {
            super(name, jointNumber, rootJoint, jointMap);
        }

        @Override
        public void setPose(yesman.epicfight.api.animation.Pose pose) {
            super.setPose(pose);
            this.applyOverride();
        }

        void applyOverride() {
            OpenMatrix4f[] mine = this.override;
            OpenMatrix4f[] theirs = this.getPoseMatrices();

            if (mine == null || theirs == null) {
                return;
            }

            for (int i = 0; i < theirs.length && i < mine.length; i++) {
                if (theirs[i] != null && mine[i] != null) {
                    theirs[i].load(mine[i]);
                }
            }
        }
    }

    private static final Map<LivingEntityPatch<?>, HandedArmature> standIns = new java.util.WeakHashMap<>();
    private static final Map<LivingEntityPatch<?>, Armature> standInSources = new java.util.WeakHashMap<>();
    private static boolean standInFailed;
    private static java.lang.reflect.Field armatureField;
    private static boolean armatureFieldLooked;

    @Nullable
    private static Armature standIn(LivingEntityPatch<?> patch, OpenMatrix4f[] joints, float partialTicks) {
        if (standInFailed) {
            return null;
        }

        try {
            Armature source = patch.getArmature();

            if (!(source instanceof yesman.epicfight.model.armature.HumanoidArmature)) {
                return null;
            }

            HandedArmature stand = standIns.get(patch);

            if (stand == null || standInSources.get(patch) != source) {
                Armature copy = source.deepCopy();
                Map<String, Joint> byName = new java.util.HashMap<>();
                List<Joint> all = new ArrayList<>();
                gather(copy.rootJoint, all);

                for (Joint joint : all) {
                    byName.put(joint.getName(), joint);
                }

                stand = new HandedArmature("epicysm_stand_in", copy.getJointNumber(), copy.rootJoint, byName);
                standIns.put(patch, stand);
                standInSources.put(patch, source);
            }

            stand.override = joints;

            if (patch.getAnimator() != null) {
                yesman.epicfight.api.animation.Pose pose = patch.getAnimator().getPose(partialTicks);

                if (pose != null) {
                    stand.setPose(pose);
                } else {
                    stand.applyOverride();
                }
            } else {
                stand.applyOverride();
            }

            return stand;
        } catch (Throwable t) {
            standInFailed = true;
            EpicYsm.LOGGER.warn("Could not build a stand-in armature from this Epic Fight's; add-on weapons that"
                    + " pose the armature themselves will sit at vanilla proportions on encrypted models.", t);
            return null;
        }
    }

    /** Puts an armature into the patch and returns the one that was there, or null if the field cannot be reached. */
    @Nullable
    private static Object swapArmature(LivingEntityPatch<?> patch, Armature armature) {
        try {
            if (!armatureFieldLooked) {
                armatureFieldLooked = true;

                for (Class<?> type = patch.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                    try {
                        java.lang.reflect.Field field = type.getDeclaredField("armature");

                        if (Armature.class.isAssignableFrom(field.getType()) && field.trySetAccessible()) {
                            armatureField = field;
                            break;
                        }
                    } catch (NoSuchFieldException ignored) {
                    }
                }

                if (armatureField == null) {
                    EpicYsm.LOGGER.warn("This Epic Fight keeps the entity's armature somewhere this mod does not know;"
                            + " add-on weapons that pose the armature themselves will sit at vanilla proportions on"
                            + " encrypted models.");
                }
            }

            if (armatureField == null) {
                return null;
            }

            Object before = armatureField.get(patch);
            armatureField.set(patch, armature);
            return before;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean complained;
    private static boolean saidOffhand;

    private static void complain(String what, Throwable t) {
        if (!complained) {
            complained = true;
            EpicYsm.LOGGER.warn("Could not reach {}; the held item is left to Yes Steve Model. Switch back for"
                    + " good in the settings screen", what, t);
        }
    }

    /** The vanilla scale attribute, one on any player that has not been resized. */
    private static float entityScale(AbstractClientPlayer player) {
        try {
            float scale = player.getScale();
            return Float.isFinite(scale) && scale > 0.01F ? scale : 1.0F;
        } catch (Throwable t) {
            return 1.0F;
        }
    }

    /**
     * Whether Yes Steve Model gives this item its quarter-again.
     *
     * Read out of its bytecode: the item layer scales by 1.25 for a gun from
     * Superb Warfare and for nothing else. This mod used to divide 1.25 out
     * of every item's matrix, and every sword came out a fifth too small.
     */
    private static boolean quarterAgain(ItemStack stack) {
        for (Class<?> type = stack.getItem().getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            if (type.getName().equals("com.atsuishio.superbwarfare.item.gun.GunItem")) {
                return true;
            }
        }

        return false;
    }

    /**
     * The size the model is drawn at, measured off the matrix Yes Steve
     * Model was about to draw the held item with: the render's space, times
     * the model's size, times the quarter-again for a gun, times how much
     * the item renderer shrinks that item for a hand, times the player's own
     */
    public static float drawnSize(AbstractClientPlayer player, Matrix4f itemSpace, float screen) {
        try {
            ItemStack main = player.getMainHandItem();

            if (main.isEmpty() || screen < 1.0E-4F) {
                return 0.0F;
            }

            Matrix4f drawn = displayFrame(player, main, false);

            if (drawn == null) {
                return 0.0F;
            }

            float shrink = (float) Math.cbrt(Math.abs(drawn.determinant()));
            float extra = quarterAgain(main) ? 1.25F : 1.0F;
            float size = (float) Math.cbrt(Math.abs(itemSpace.determinant()))
                    / (extra * shrink * screen * entityScale(player));
            return Float.isFinite(size) && size > 0.05F && size < 8.0F ? size : 0.0F;
        } catch (Throwable t) {
            return 0.0F;
        }
    }

    /**
     * Where Yes Steve Model's hand locator is, in the render's space, read
     * back off the matrix it was about to draw the item with.
     */
    @Nullable
    private static Vector3f locatorPoint(AbstractClientPlayer player, ItemStack stack, boolean off, Matrix4f captured) {
        Matrix4f display = displayFrame(player, stack, off);

        if (display == null) {
            return null;
        }

        try {
            // captured = locator * T(0, -0.0625, -0.1) * Rx(-90) * [gun: T(0,0,0.1) S(1.25)] * display
            Matrix4f layer = new Matrix4f().translate(0.0F, -0.0625F, -0.1F)
                    .rotateX((float) Math.toRadians(-90.0));

            if (quarterAgain(stack)) {
                layer.translate(0.0F, 0.0F, 0.1F).scale(1.25F);
            }

            Vector3f origin = new Matrix4f(layer).mul(display).invert().transformPosition(new Vector3f());
            Vector3f out = captured.transformPosition(origin);
            return out.isFinite() ? out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Where Epic Fight is about to draw the item's origin, in the render's
     * space: the stack as it stands, times the renderer's own correction for
     * this hand - the same call it makes itself. If that is not to be had,
     * the tool joint stands in.
     */
    @Nullable
    private static Vector3f itemOrigin(RenderItemBase renderer, LivingEntityPatch<?> patch, InteractionHand hand,
                                       OpenMatrix4f[] joints, PoseStack poseStack, @Nullable Matrix4f tool,
                                       float scale) {
        Matrix4f stack = new Matrix4f(poseStack.last().pose());

        try {
            OpenMatrix4f correction = renderer.getCorrectionMatrix(patch, hand, joints);

            if (correction != null) {
                Vector3f out = stack.mul(OpenMatrix4f.exportToMojangMatrix(correction))
                        .transformPosition(new Vector3f());

                if (out.isFinite()) {
                    return out;
                }
            }
        } catch (Throwable ignored) {
            // An add-on renderer with a correction of its own shape.
        }

        if (tool == null) {
            return null;
        }

        Vector3f out = stack.transformPosition(tool.getTranslation(new Vector3f()).mul(scale));
        return out.isFinite() ? out : null;
    }

    /**
     * The item's own hand transform, as the item renderer applies it before
     * the first vertex, or null for an item drawn by a custom renderer.
     */
    @Nullable
    private static Matrix4f displayFrame(AbstractClientPlayer player, ItemStack stack, boolean off) {
        try {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.world.item.ItemDisplayContext held = off
                    ? net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                    : net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
            net.minecraft.client.resources.model.BakedModel baked = client.getItemRenderer()
                    .getModel(stack, player.level(), player, player.getId() + held.ordinal());

            if (baked == null || baked.isCustomRenderer()) {
                return null;
            }

            PoseStack scratch = new PoseStack();
            baked.getTransforms().getTransform(held).apply(off, scratch);
            scratch.translate(-0.5F, -0.5F, -0.5F);
            return new Matrix4f(scratch.last().pose());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The solve's joints in blocks, in Epic Fight's flat array. Distances
     * scale by the model's size; a turn is a turn whatever the ruler.
     */
    @Nullable
    private static OpenMatrix4f[] blocks(@Nullable Armature armature, Map<String, Matrix4f> drawn, float size) {
        if (armature == null || armature.rootJoint == null) {
            return null;
        }

        List<Joint> all = new ArrayList<>();
        gather(armature.rootJoint, all);
        int most = 0;

        for (Joint joint : all) {
            most = Math.max(most, joint.getId() + 1);
        }

        if (most == 0) {
            return null;
        }

        OpenMatrix4f[] out = new OpenMatrix4f[most];

        for (int i = 0; i < most; i++) {
            out[i] = new OpenMatrix4f();
        }

        for (Joint joint : all) {
            Matrix4f found = drawn.get(joint.getName());

            if (found != null) {
                Matrix4f scaled = new Matrix4f(found);
                scaled.setTranslation(found.getTranslation(new Vector3f()).mul(size));
                out[joint.getId()] = OpenMatrix4f.importFromMojangMatrix(scaled);
            }
        }

        return out;
    }

    private static void gather(Joint joint, List<Joint> out) {
        out.add(joint);

        for (Joint child : joint.getSubJoints()) {
            gather(child, out);
        }
    }

    private static float worstGap;
    private static float lastGap = Float.NaN;
    private static float worstGapStep;
    private static int frames;
    private static boolean said;
    private static boolean saidFirst;

    /**
     * The distance between where Epic Fight draws the item's origin and the
     * nearest place Yes Steve Model would have drawn one, and how much that
     * changes between frames. Written once at the start and once after six
     * hundred frames in the world. A steady few centimetres is the two
     */
    private static void watch(float gap, float scale, String rendererName) {
        if (!saidFirst) {
            saidFirst = true;
            com.argorice.epicysm.client.Diag.info("Held item: the open models' path in the body's own space. Epic Fight's model"
                    + " matrix built with the body's yaw, its own item renderer ({}), this mod's joints handed"
                    + " over at {} of the model's unit so that the matrix's 0.9375 brings them back to the size"
                    + " the body is drawn at. Nothing is nudged. The hand locator as Yes Steve Model drew it"
                    + " is {} blocks from this mod's tool joint right now - the two readings of one point.",
                    rendererName, Math.round(scale * 1000.0F) / 1000.0F,
                    Float.isNaN(gap) ? "an unknown distance" : String.valueOf(Math.round(gap * 1000.0F) / 1000.0F));
        }

        if (said || Float.isNaN(gap)) {
            return;
        }

        if (!Float.isNaN(lastGap)) {
            worstGapStep = Math.max(worstGapStep, Math.abs(gap - lastGap));
        }

        worstGap = Math.max(worstGap, gap);
        lastGap = gap;

        if (++frames >= 600) {
            said = true;
            com.argorice.epicysm.client.Diag.info("Held item: over {} frames the hand locator as Yes Steve Model drew it was never"
                    + " further than {} blocks from this mod's tool joint, and that distance never changed by"
                    + " more than {} blocks between two frames. Small and steady means the arm's chain is"
                    + " right; large means a bone between the body and the hand is not where this mod thinks.",
                    frames,
                    Math.round(worstGap * 1000.0F) / 1000.0F, Math.round(worstGapStep * 1000.0F) / 1000.0F);
        }
    }
}
