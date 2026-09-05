package com.argorice.epicysm.client.ysm;

import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.JointTransform;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import com.argorice.epicysm.EpicYsm;

/** Opens a window inside Yes Steve Model's own render call. */
public final class YsmRenderBridge {
    private static boolean inside;
    private static boolean disabled;
    private static int rendersWithoutWindow;

    /** Renders that ended in an exception; a few are forgiven, more are not. */
    private static int failures;
    private static final int FAILURES_ALLOWED = 3;

    /** The last size a model was measured being drawn at. */
    private static float lastScale;
    private static boolean saidStep;

    private static float round(float value) {
        return Math.round(value * 1000.0F) / 1000.0F;
    }

    /** Puts the bridge back in play, e.g. after the settings. */
    public static void enable() {
        disabled = false;
        rendersWithoutWindow = 0;
        failures = 0;
    }

    private YsmRenderBridge() {
    }

    /**
     * Re-runs the foreign render with the window in place. Returns true when
     * the event was taken over, in which case the caller must not do anything
     * else with it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean intercept(RenderLivingEvent.Pre<?, ?> event, AbstractClientPlayer player,
                                    ResourceLocation texture, boolean yieldingToYsm) {
        if (inside || disabled) {
            // The nested pass: let it run untouched, this is YSM drawing.
            return false;
        }

        Object renderer = event.getRenderer();

        if (!(renderer instanceof EntityRenderer)) {
            return false;
        }

        YsmSkeletonOverlay overlay = YsmSkeletonOverlay.of(player);
        boolean poseWanted = yieldingToYsm && com.argorice.epicysm.client.EpicYsmConfig.skeletonOverlay()
                && overlay.wants(player, texture);
        boolean scaleWanted = false;

        if (!poseWanted && !scaleWanted) {
            return false;
        }

        // Which picture this is: the world, or a screen showing the player.
        //
        float size = (float) Math.cbrt(Math.abs(event.getPoseStack().last().pose().determinant()));
        YsmPoseSolver.setWorldFrame(Float.isFinite(size) && size > 0.5F && size < 2.0F);

        // This model's own ruler, before anything is posed with it.
        YsmPoseSolver.setModelScale(rulerFor(texture));

        if (poseWanted) {
            overlay.prepare(player, renderer, texture);
        }

        float partialTicks = event.getPartialTick();

        // Where the animation carries the whole body, and how big it draws
        // it. The model's bones take rotations only, so the body would stay
        // on the spot Yes Steve Model puts it while an animation carries it
        // away or shrinks it to nothing - the way a character is made to
        // vanish mid-attack. So the carry goes on the stack round the
        // model's render. Round that alone: the held item's joints already
        // travel with the animation, the solver moves them, so the item is
        // drawn after, on the plain stack. Only while Epic Fight's pose is
        // what the model shows: in bed, or out of battle, the model's own
        // animation places the body.
        float[] carry = poseWanted && YsmSkeletonOverlay.fighting(player) ? bodyCarry(player, partialTicks) : null;
        overlay.carriedBy(carry);

        if (carry != null && carry[3] < VANISHED && carry[4] < VANISHED && carry[5] < VANISHED) {
            event.setCanceled(true);
            com.argorice.epicysm.client.compat.LookOwners.hiddenByAnimation(player, renderer);
            return true;
        }

        event.getPoseStack().pushPose();

        if (carry != null) {
            event.getPoseStack().translate(carry[0], carry[1], carry[2]);
            event.getPoseStack().scale(carry[3], carry[4], carry[5]);
        }

        if (scaleWanted) {
            YsmScaleProbe.get().begin(texture, event.getPoseStack().last().pose());
        }

        Window window = new Window(event.getMultiBufferSource(), player, partialTicks,
                event.getPoseStack(), poseWanted, scaleWanted, texture);

        event.setCanceled(true);
        inside = true;

        com.mojang.blaze3d.vertex.PoseStack.Pose mark = PoseStacks.mark(event.getPoseStack());

        try {
            float yaw = Mth.rotLerp(partialTicks, player.yRotO, player.getYRot());
            ((EntityRenderer) renderer).render(player, yaw, partialTicks, event.getPoseStack(), window,
                    event.getPackedLight());
        } catch (Throwable t) {
            // Whatever it pushed before failing is still on the stack, and
            // at the end of the frame that stops the game with a message
            // that names nobody. Cleared here, the failure is one line in
            // the log and one player drawn by Yes Steve Model alone.
            PoseStacks.unwind(event.getPoseStack(), mark);

            // Once may be another mod's bad frame; a pattern is this mod's
            // problem, and then it steps aside for the rest of the session.
            if (++failures >= FAILURES_ALLOWED) {
                EpicYsm.LOGGER.warn("Yes Steve Model refused to be re-rendered with Epic Fight's pose {} times;"
                        + " leaving its rendering alone from now on", failures, t);
                disabled = true;
                YsmSkeletonOverlay.resetAll();
            } else {
                EpicYsm.LOGGER.warn("Yes Steve Model refused to be re-rendered with Epic Fight's pose"
                        + " (attempt {} of {})", failures, FAILURES_ALLOWED, t);
            }

            return true;
        } finally {
            inside = false;
            event.getPoseStack().popPose();
        }

        // The model is drawn; the item in its hand is not, because the copy
        // Yes Steve Model would have drawn was collapsed to nothing on the
        if (poseWanted && EpicFightItems.own() && window.sawItem()) {
            try {
                EpicFightItems.drawAt(player, event.getPoseStack(), event.getMultiBufferSource(),
                        event.getPackedLight(), partialTicks,
                        net.minecraft.world.InteractionHand.MAIN_HAND, window.itemSpaces);

                if (!player.getOffhandItem().isEmpty()) {
                    EpicFightItems.drawAt(player, event.getPoseStack(), event.getMultiBufferSource(),
                            event.getPackedLight(), partialTicks,
                            net.minecraft.world.InteractionHand.OFF_HAND, window.itemSpaces);
                }

                // And a second reading of the size this model is drawn at,
                // off the matrix Yes Steve Model was about to draw the item
                float drawn = EpicFightItems.drawnSize(player, window.itemSpaces.get(0), size);

                if (drawn > 0.0F && texture != null) {
                    noteMeasured(texture, drawn);
                }
            } catch (Throwable ignored) {
                // EpicFightItems says so itself, once, in the log.
            }
        }

        if (scaleWanted && com.argorice.epicysm.client.ModelManager.get().epicFightDraws(player)) {
            // That was Epic Fight drawing, not Yes Steve Model.
            YsmScaleProbe.get().discard(texture);
        }

        // If item after item goes by and none of them is ever the one in
        // the hand, the name being looked for is not in this model's render
        if (poseWanted && EpicFightItems.own() && !loose && window.sawAnyItem && window.itemSpaces.isEmpty()
                && ++rendersWithoutHandItem > 120) {
            loose = true;
            com.argorice.epicysm.client.Diag.info("Held item: over {} renders this model drew items through the item renderer but"
                    + " never one asked for by Yes Steve Model's own item layer, so the first item drawn is"
                    + " taken instead. That may be a hat or a pack rather than the weapon - if the weapon is"
                    + " in an odd place, this line is why", rendersWithoutHandItem);
        }

        if (window.opened) {
            rendersWithoutWindow = 0;
        } else if (++rendersWithoutWindow > 60) {
            disabled = true;
            EpicYsm.LOGGER.warn("Skeleton overlay: Yes Steve Model draws this player without ever asking for a"
                    + " render buffer, so there is no moment to place Epic Fight's pose in. Its own animations"
                    + " are left alone.");
            YsmSkeletonOverlay.resetAll();
        }

        return true;
    }

    /**
     * Buffer source that poses the skeleton the moment it is first asked,
     * and reads the model's size off the pose stack at the first vertex.
     */
    private static final class Window extends MultiBufferSource.BufferSource {
        /**
         * Never drawn into. The window is a {@code BufferSource} only so that
         * a layer which casts the buffer source it is handed - as armor,
         * cosmetic and weapon add-ons commonly do to flush it - is not
         * stopped by a ClassCastException halfway through the render. Every
         * request is passed to the real source underneath.
         */
        private static final com.mojang.blaze3d.vertex.ByteBufferBuilder UNUSED = new com.mojang.blaze3d.vertex.ByteBufferBuilder(256);

        private final MultiBufferSource delegate;
        private final AbstractClientPlayer player;
        private final float partialTicks;
        private final com.mojang.blaze3d.vertex.PoseStack poseStack;
        private final boolean poseWanted;
        private final boolean scaleWanted;
        private boolean opened;
        private boolean measured;
        private boolean sawAnyItem;

        /** The stack as this render was handed over, before Yes Steve Model. */
        private final org.joml.Matrix4f entry;

        /** The model's own space, and how much larger it is drawn. */
        private org.joml.Matrix4f root;
        private float scale;

        /**
         * Every place Yes Steve Model was about to draw a held item this
         * render. More than one hand's worth: a model with a sheath draws
         * the same sword on the hip as well as in the hand, through the
         * same layer, and which of the two came first was luck. Taking the
         */
        private final List<org.joml.Matrix4f> itemSpaces = new ArrayList<>();

        private final ResourceLocation texture;

        Window(MultiBufferSource delegate, AbstractClientPlayer player, float partialTicks,
               com.mojang.blaze3d.vertex.PoseStack poseStack, boolean poseWanted, boolean scaleWanted,
               ResourceLocation texture) {
            super(UNUSED, new java.util.LinkedHashMap<>());
            this.texture = texture;
            this.delegate = delegate;
            this.player = player;
            this.partialTicks = partialTicks;
            this.poseStack = poseStack;
            this.poseWanted = poseWanted;
            this.scaleWanted = scaleWanted;
            this.entry = new org.joml.Matrix4f(poseStack.last().pose());
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            if (!this.opened) {
                this.opened = true;

                if (this.poseWanted) {
                    YsmSkeletonOverlay.of(this.player).onDraw(this.player, this.partialTicks);
                }
            }

            // The space the model is drawn in, taken from the right request.
            //
            boolean wanted = this.itemSpaces.size() < 8 && this.poseWanted && EpicFightItems.own()
                    && !this.player.getMainHandItem().isEmpty()
                    && YsmSkeletonOverlay.fighting(this.player);

            if (wanted && anyItemRenderer()) {
                if (handItem() != null || loose) {
                    sayCaller();
                    this.itemSpaces.add(new org.joml.Matrix4f(this.poseStack.last().pose()));
                    return NOWHERE;
                }

                // An item was drawn and it was not the one in the hand.
                // Worth remembering: if that is all this model ever draws,
                this.sawAnyItem = true;
            }

            VertexConsumer buffer = this.delegate.getBuffer(renderType);
            boolean watching = (this.scaleWanted || this.root != null || this.poseWanted) && !this.measured;
            return watching ? new FirstVertexWatcher(buffer, this) : buffer;
        }

        // A flush asked of this window is a flush of the real source; there
        // is nothing of its own to flush.

        @Override
        public void endBatch() {
            if (this.delegate instanceof MultiBufferSource.BufferSource source) {
                source.endBatch();
            }
        }

        @Override
        public void endLastBatch() {
            if (this.delegate instanceof MultiBufferSource.BufferSource source) {
                source.endLastBatch();
            }
        }

        @Override
        public void endBatch(RenderType renderType) {
            if (this.delegate instanceof MultiBufferSource.BufferSource source) {
                source.endBatch(renderType);
            }
        }

        /** Whether this is the space the model itself is drawn in. */
        private static boolean modelSpace(@javax.annotation.Nullable org.joml.Matrix4f candidate) {
            if (candidate == null || candidate.m11() <= 0.0F) {
                return false;
            }

            float size = (float) Math.cbrt(Math.abs(candidate.determinant()));
            return Float.isFinite(size) && size >= 0.05F && size <= 4.0F;
        }

        /** Whether the moment the item is drawn in was seen this frame. */
        boolean sawItem() {
            return !this.itemSpaces.isEmpty();
        }

        void onFirstVertex() {
            if (this.measured) {
                return;
            }

            this.measured = true;
            this.measureDrawnSize();

            if (this.scaleWanted) {
                YsmScaleProbe.get().finish(this.poseStack.last().pose());
            }

            if (this.root == null) {
                return;
            }

            // Everything between the two readings, as one matrix. Its
            // columns are the model's scale on each axis, whatever turning
            try {
                org.joml.Matrix4f step = this.root.invert(new org.joml.Matrix4f())
                        .mul(this.poseStack.last().pose());
                float measured = (float) Math.cbrt(Math.abs(step.determinant()));

                // Everything Yes Steve Model does between being asked where
                // to draw and drawing the first thing. Only the size of it
                if (!saidStep) {
                    saidStep = true;
                    com.argorice.epicysm.client.Diag.info("Skeleton overlay: between asking for a buffer and the first vertex, Yes"
                            + " Steve Model moves the model by ({}, {}, {}) and resizes it by {}. A move here is"
                            + " missing from anything this mod draws beside the model.",
                            round(step.m30()), round(step.m31()), round(step.m32()), round(measured));
                }

                // A bone that has been put away has a scale of nothing, and
                // if the first thing drawn happens to be one of those the
                if (Float.isFinite(measured) && measured > 0.01F && measured < 8.0F) {
                    lastScale = measured;
                }
            } catch (Throwable ignored) {
                // A pose stack that cannot be inverted says nothing.
            }

            this.scale = lastScale;
        }

        /** How big this model is actually drawn, for the travel ruler. */
        private void measureDrawnSize() {
            if (!this.poseWanted || !YsmPoseSolver.worldFrame()) {
                return;
            }

            try {
                float before = (float) Math.cbrt(Math.abs(this.entry.determinant()));
                float after = (float) Math.cbrt(Math.abs(this.poseStack.last().pose().determinant()));

                if (!(before > 1.0E-6F) || !(after > 1.0E-6F)) {
                    return;
                }

                // Blocks per pixel, and then the size that is - a bedrock
                // model is sixteen pixels to the block before its own scale.
                float perPixel = after / before;
                float drawn = perPixel * 16.0F;

                if (!Float.isFinite(drawn) || drawn < 0.05F || drawn > 8.0F) {
                    return;
                }

                if (!saidDrawnSize) {
                    saidDrawnSize = true;
                    com.argorice.epicysm.client.Diag.info("Skeleton overlay: this model is drawn at {} of a block to its own"
                            + " unit, measured between the start of its render and its first vertex.",
                            round(drawn));
                }
            } catch (Throwable ignored) {
                // A stack that cannot be read says nothing about size.
            }
        }
    }

    private static boolean saidDrawnSize;

    /** The size each model was measured being drawn at, by its skin. */
    private static final java.util.Map<ResourceLocation, Float> drawnSizes = new java.util.HashMap<>();
    private static final java.util.Map<ResourceLocation, List<Float>> readings = new java.util.HashMap<>();
    private static final Set<ResourceLocation> saidRuler = new HashSet<>();

    /**
     * The ruler for this model: the size Yes Steve Model itself says it draws
     * the model at, or, failing that, the settled measurement. Zero means
     * unknown, and the solver falls back to seven tenths.
     */
    static float rulerFor(@Nullable ResourceLocation texture) {
        float[] own = YsmLiveSkeleton.drawnSize(texture);

        if (own != null) {
            // One number for three axes; the two agree on every model but a
            // handful, and the depth is drawn at the width.
            float ruler = (float) Math.cbrt(own[0] * own[0] * own[1]);

            if (Float.isFinite(ruler) && ruler > 0.05F && ruler < 8.0F) {
                return ruler;
            }
        }

        // Not the measurement off the held item: it carries whatever the
        // hand locator's own scale is - three quarters on one model - and
        return 0.0F;
    }

    private static void noteMeasured(ResourceLocation texture, float drawn) {
        if (drawnSizes.containsKey(texture)) {
            return;
        }

        List<Float> seen = readings.computeIfAbsent(texture, k -> new ArrayList<>());
        seen.add(drawn);

        if (seen.size() < 20) {
            return;
        }

        List<Float> sorted = new ArrayList<>(seen);
        java.util.Collections.sort(sorted);
        float settled = sorted.get(sorted.size() / 2);
        drawnSizes.put(texture, settled);
        readings.remove(texture);

        if (saidRuler.add(texture)) {
            float[] own = YsmLiveSkeleton.drawnSize(texture);

            if (own != null) {
                com.argorice.epicysm.client.Diag.info("Skeleton overlay: measured off the held item, this model is drawn at {} of"
                        + " a block to its own unit; Yes Steve Model's own description says {} wide, {} tall."
                        + " The description is the ruler in use. Where the two differ, the difference is the"
                        + " hand locator's own scale.", round(settled), own[0], own[1]);
            } else {
                com.argorice.epicysm.client.Diag.info("Skeleton overlay: measured off the held item, this model is drawn at {} of"
                        + " a block to its own unit, give or take the hand locator's own scale. Yes Steve"
                        + " Model's own description was not found, so seven tenths is in use, not this.",
                        round(settled));
            }
        }
    }

    /** Passes everything through, and says when the first vertex goes by. */
    /** Whether the code asking for a buffer right now is drawing a held item. */
    /** Below this, a body scaled by its animation is not there to be seen. */
    private static final float VANISHED = 0.02F;

    /**
     * The animation's hold on the whole body this frame: the offset it
     * moves the root by, in the frame of the render event's stack, and the
     * scale it draws the body at - or null when it leaves both alone.
     */
    @Nullable
    private static float[] bodyCarry(AbstractClientPlayer player, float partialTicks) {
        try {
            LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(player, LivingEntityPatch.class);

            if (patch == null || patch.getAnimator() == null || patch.getArmature() == null) {
                return null;
            }

            Pose pose = patch.getAnimator().getPose(partialTicks);
            JointTransform root = pose == null ? null : pose.getJointTransformData().get("Root");

            if (root == null) {
                return null;
            }

            Vec3f scale = root.scale();
            boolean atSize = Math.abs(scale.x - 1.0F) < 0.001F && Math.abs(scale.y - 1.0F) < 0.001F
                    && Math.abs(scale.z - 1.0F) < 0.001F;

            if (atSize) {
                return null;
            }

            // Only the size. Where the animation carries the body is already
            // in the bones: the solver gives every driven bone the whole
            // travel of its joint, root and all, and Yes Steve Model draws
            // the bones where they are put. Carrying the stack by the same
            // distance moved the body twice as far as the held item - a
            // third of a block under the hand in a low stance, a body sunk
            // into the bed - which is what the numbers Yes Steve Model
            // reports for the hand showed, once they were asked for.
            Joint joint = patch.getArmature().rootJoint;
            OpenMatrix4f posed = root.getAnimationBoundMatrix(joint, new OpenMatrix4f());
            float sx = (float) Math.sqrt(posed.m00 * posed.m00 + posed.m01 * posed.m01 + posed.m02 * posed.m02);
            float sy = (float) Math.sqrt(posed.m10 * posed.m10 + posed.m11 * posed.m11 + posed.m12 * posed.m12);
            float sz = (float) Math.sqrt(posed.m20 * posed.m20 + posed.m21 * posed.m21 + posed.m22 * posed.m22);
            return new float[] { 0.0F, 0.0F, 0.0F, sx, sy, sz };
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether anything at all is being drawn through the item renderer.
     *
     * Deliberately loose: it says an item is being drawn, not which. It is
     * the safety net for the strict check below, and the counter that decides
     * when to fall back to it.
     */
    private static boolean anyItemRenderer() {
        try {
            return StackWalker.getInstance().walk(frames -> frames.limit(48).anyMatch(frame -> {
                String name = frame.getClassName();
                return name.equals("net.minecraft.client.renderer.ItemInHandRenderer")
                        || name.equals("net.minecraft.client.renderer.entity.ItemRenderer");
            }));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Set once the strict check has plainly never been going to match. */
    private static boolean loose;
    private static int rendersWithoutHandItem;

    /** Whether the item being drawn this instant is the one in the hand. */
    @javax.annotation.Nullable
    private static String handItem() {
        try {
            return StackWalker.getInstance().walk(frames -> {
                boolean inside = false;

                for (StackWalker.StackFrame frame : frames.limit(48).toList()) {
                    String name = frame.getClassName();
                    boolean renderer = name.equals("net.minecraft.client.renderer.ItemInHandRenderer")
                            || name.equals("net.minecraft.client.renderer.entity.ItemRenderer");

                    if (renderer) {
                        inside = true;
                        continue;
                    }

                    if (!inside) {
                        continue;
                    }

                    // The first frame below the item renderer is whoever
                    // asked for an item to be drawn.
                    boolean hand = name.startsWith("com.elfmcys.yesstevemodel")
                            || name.contains("ItemInHandLayer");
                    return hand ? name : null;
                }

                return null;
            });
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean saidCaller;

    private static void sayCaller() {
        if (saidCaller) {
            return;
        }

        String caller = handItem();

        if (caller != null) {
            saidCaller = true;
            com.argorice.epicysm.client.Diag.info("Held item: the draw taken for the one in the hand is the one asked for by {}."
                    + " Anything else drawn through the item renderer during this model - a hat, a pack, a"
                    + " trinket - is left alone; taking the first of those for the weapon is what put it in"
                    + " odd places", caller);
        }
    }

    /** Takes vertices and does nothing with them. */
    private static final VertexConsumer NOWHERE = new VertexConsumer() {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    };

    private static final class FirstVertexWatcher implements VertexConsumer {
        private final VertexConsumer delegate;
        private final Window window;

        FirstVertexWatcher(VertexConsumer delegate, Window window) {
            this.delegate = delegate;
            this.window = window;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.window.onFirstVertex();
            this.delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            this.delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            this.delegate.setNormal(x, y, z);
            return this;
        }
    }
}
