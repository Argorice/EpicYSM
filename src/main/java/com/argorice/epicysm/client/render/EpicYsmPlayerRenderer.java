package com.argorice.epicysm.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import org.joml.Vector4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.BeeStingerLayer;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.NeoForge;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.event.EpicFightClientEventHooks;
import yesman.epicfight.api.client.event.types.render.PrepareModelEvent;
import yesman.epicfight.api.client.model.Meshes;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec2i;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.client.renderer.patched.entity.PatchedLivingEntityRenderer;
import yesman.epicfight.client.renderer.patched.layer.PatchedArrowLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedBeeStingerLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedCapeLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedElytraLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedHeadLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedItemInHandLayer;
import yesman.epicfight.client.renderer.patched.layer.WearableItemLayer;
import yesman.epicfight.client.world.capabilites.entitypatch.player.AbstractClientPlayerPatch;
import yesman.epicfight.mixin.client.MixinEntityRenderer;
import yesman.epicfight.mixin.client.MixinLivingEntityRenderer;

import com.argorice.epicysm.EpicYsm;
import com.argorice.epicysm.client.ModelManager;
import com.argorice.epicysm.client.convert.ConvertedModel;

/** Replacement for Epic Fight's patched player renderer. */
public class EpicYsmPlayerRenderer extends PatchedLivingEntityRenderer<
        AbstractClientPlayer,
        AbstractClientPlayerPatch<AbstractClientPlayer>,
        HumanoidModel<AbstractClientPlayer>,
        LivingEntityRenderer<AbstractClientPlayer, HumanoidModel<AbstractClientPlayer>>,
        HumanoidMesh> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public EpicYsmPlayerRenderer(EntityRendererProvider.Context context, EntityType<?> entityType) {
        super(context, entityType);

        // Same patched layers PHumanoidRenderer and PPlayerRenderer install.
        // Raw casts: some of them are declared against PlayerModel, but they
        this.addPatchedLayer(ElytraLayer.class, new PatchedElytraLayer<>());
        this.addPatchedLayer(ItemInHandLayer.class, new PatchedItemInHandLayer<>());
        this.addPatchedLayer(HumanoidArmorLayer.class, new ArmorSwitch(new WearableItemLayer<>(Meshes.BIPED, false, context.getModelManager())));
        this.addPatchedLayer(CustomHeadLayer.class, new PatchedHeadLayer<>());
        this.addPatchedLayer(ArrowLayer.class, (yesman.epicfight.client.renderer.patched.layer.PatchedLayer) new PatchedArrowLayer(context));
        this.addPatchedLayer(BeeStingerLayer.class, (yesman.epicfight.client.renderer.patched.layer.PatchedLayer) new PatchedBeeStingerLayer());
        this.addPatchedLayer(CapeLayer.class, (yesman.epicfight.client.renderer.patched.layer.PatchedLayer) new PatchedCapeLayer());
        this.addPatchedLayer(PlayerItemInHandLayer.class, new PatchedItemInHandLayer<>());
    }

    /** True while the player on screen is drawn with a converted YSM model. */
    private boolean drawingConverted;

    /**
     * Epic Fight's armor layer, skipped on converted models unless the
     * settings ask for armor there: a YSM model is drawn as its author
     * made it, the way Yes Steve Model itself shows it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private final class ArmorSwitch extends yesman.epicfight.client.renderer.patched.layer.PatchedLayer<
            AbstractClientPlayer, AbstractClientPlayerPatch<AbstractClientPlayer>, HumanoidModel<AbstractClientPlayer>,
            HumanoidArmorLayer<AbstractClientPlayer, HumanoidModel<AbstractClientPlayer>, HumanoidModel<AbstractClientPlayer>>> {
        private final WearableItemLayer armor;

        ArmorSwitch(WearableItemLayer armor) {
            this.armor = armor;
        }

        @Override
        protected void renderLayer(AbstractClientPlayerPatch<AbstractClientPlayer> entitypatch, AbstractClientPlayer entity,
                                   HumanoidArmorLayer<AbstractClientPlayer, HumanoidModel<AbstractClientPlayer>, HumanoidModel<AbstractClientPlayer>> layer,
                                   PoseStack poseStack, MultiBufferSource buffer, int packedLight, OpenMatrix4f[] poses,
                                   float bob, float yRot, float xRot, float partialTicks) {
            if (!EpicYsmPlayerRenderer.this.drawingConverted || com.argorice.epicysm.client.EpicYsmConfig.armorOnModels()) {
                this.armor.renderLayer(entitypatch, entity, layer, poseStack, buffer, packedLight, poses, bob, yRot, xRot, partialTicks);
            }
        }
    }

    @Override
    public void render(AbstractClientPlayer entity, AbstractClientPlayerPatch<AbstractClientPlayer> entitypatch,
                       LivingEntityRenderer<AbstractClientPlayer, HumanoidModel<AbstractClientPlayer>> renderer,
                       MultiBufferSource buffer, PoseStack poseStack, int packedLight, float partialTicks) {
        Object rendererObject = renderer;

        // When another mod (YSM) re-renders the player through its own
        // renderer, its texture is the only public clue about which model
        if (!(rendererObject instanceof PlayerRenderer)) {
            try {
                ModelManager.get().noteForeignRenderer(entity, renderer, renderer.getTextureLocation(entity));
            } catch (Throwable ignored) {
                // A foreign renderer may not be ready to answer; skip the hint.
            }
        }

        ConvertedModel model = ModelManager.get().modelFor(entity);
        this.drawingConverted = model != null;

        // Only a converted model means Epic Fight is really the one drawing
        // this player. Saying so unconditionally, as this used to at the top
        if (model != null) {
            ModelManager.get().noteEpicFightRendered(entity);
        }

        // Nameplate, identical to PatchedEntityRenderer.render.
        RenderNameTagEvent nameTagEvent = new RenderNameTagEvent(entity, entity.getDisplayName(), renderer, poseStack, buffer, packedLight, partialTicks);
        NeoForge.EVENT_BUS.post(nameTagEvent);
        MixinEntityRenderer entityRendererAccessor = (MixinEntityRenderer) renderer;

        if (nameTagEvent.canRender().isTrue() || nameTagEvent.canRender().isDefault() && entityRendererAccessor.invokeShouldShowName(entity)) {
            entityRendererAccessor.invokeRenderNameTag(entity, nameTagEvent.getContent(), poseStack, buffer, packedLight, partialTicks);
        }

        MixinLivingEntityRenderer livingRendererAccessor = (MixinLivingEntityRenderer) renderer;
        boolean isVisible = livingRendererAccessor.invokeIsBodyVisible(entity);
        boolean isVisibleToPlayer = !isVisible && !entity.isInvisibleTo(Minecraft.getInstance().player);
        boolean isGlowing = Minecraft.getInstance().shouldEntityAppearGlowing(entity);
        RenderType renderType;

        if (model != null) {
            renderType = this.pickRenderType(model, isVisible, isVisibleToPlayer, isGlowing);
        } else if (rendererObject instanceof PlayerRenderer) {
            renderType = livingRendererAccessor.invokeGetRenderType(entity, isVisible, isVisibleToPlayer, isGlowing);
        } else {
            // Falling back to the biped mesh while a foreign renderer (YSM)
            // owns the player: that renderer's getRenderType would return the
            renderType = this.pickSkinRenderType(entity.getSkin().texture(), isVisible, isVisibleToPlayer, isGlowing);
        }

        Armature armature = model != null ? model.armature() : entitypatch.getArmature();
        poseStack.pushPose();
        this.mulPoseStack(poseStack, armature, entity, entitypatch, partialTicks);

        // Layers (held item, armor, cape, ...) need a renderer whose layer
        // list holds the vanilla layer classes our patched layers pair with.
        PlayerRenderer vanillaRenderer = rendererObject instanceof PlayerRenderer playerRenderer
                ? playerRenderer
                : vanillaPlayerRenderer(entity);

        if (vanillaRenderer != null) {
            this.prepareVanillaModel(entity, vanillaRenderer.getModel(), castRenderer(vanillaRenderer), partialTicks);
        }

        // Keep the gameplay armature posed like the original renderer does,
        // then pose the converted one with the same animation Pose.
        this.setArmaturePose(entitypatch, entitypatch.getArmature(), partialTicks);

        if (model != null) {
            this.setArmaturePose(entitypatch, armature, partialTicks);

            if (com.argorice.epicysm.client.EpicYsmConfig.physics()) {
                PhysicsAnimator.get().apply(entity, model, armature, partialTicks);
            }

            auditPose(model.id(), armature);
        }

        if (renderType != null) {
            HumanoidMesh mesh = model != null ? model.mesh() : this.getMeshProvider(entitypatch).get();
            this.prepareMeshParts(mesh, entity);
            PrepareModelEvent prepareModelEvent = new PrepareModelEvent(this, mesh, entitypatch, buffer, poseStack, packedLight, partialTicks);

            if (!EpicFightClientEventHooks.Render.PREPARE_MODEL_TO_RENDER.post(prepareModelEvent).isCanceled()) {
                Vector4f color = new Vector4f(1.0F, 1.0F, 1.0F, isVisibleToPlayer ? 0.15F : 1.0F);
                entitypatch.getEntityDecorations().modifyColor(color, partialTicks);

                int blockLight = (packedLight & 0xF0) >> 4;
                int skyLight = (packedLight & 0xF00000) >> 20;
                Vec2i lightUv = new Vec2i(blockLight, skyLight);
                entitypatch.getEntityDecorations().modifyLight(lightUv, partialTicks);
                int modifiedLight = LightTexture.pack(lightUv.x, lightUv.y);
                int overlay = this.getOverlayCoord(entity, entitypatch, partialTicks);

                mesh.draw(poseStack, buffer, renderType, modifiedLight, color.x(), color.y(), color.z(), color.w(), overlay, armature, armature.getPoseMatrices());

                entitypatch.getEntityDecorations().listDecorationOverlays().forEach(decorationOverlay -> {
                    if (decorationOverlay.shouldRender()) {
                        Vector4f overlayColor = decorationOverlay.color(partialTicks);
                        mesh.draw(poseStack, buffer, decorationOverlay.getRenderType(), modifiedLight, overlayColor.x(), overlayColor.y(), overlayColor.z(), overlayColor.w(), OverlayTexture.NO_OVERLAY, armature, armature.getPoseMatrices());
                    }
                });
            }
        }

        if (!entity.isSpectator()) {
            // Nothing of this mod's own goes in here. A readable model is
            // drawn by Epic Fight, with Epic Fight's layers, exactly as it
            reportItemLayer(vanillaRenderer, renderer, armature);
            this.renderLayer(vanillaRenderer != null ? castRenderer(vanillaRenderer) : renderer,
                    entitypatch, entity, armature.getPoseMatrices(), buffer, poseStack, packedLight, partialTicks);
        }

        if (renderType != null && Minecraft.getInstance().getEntityRenderDispatcher().shouldRenderHitBoxes()) {
            entitypatch.getClientAnimator().renderDebuggingInfoForAllLayers(poseStack, buffer, partialTicks);
        }

        poseStack.popPose();
    }

    /**
     * How many frames each model has been drawn for, so the pose can be
     * looked at more than once.
     */
    private static final java.util.Map<String, Integer> AUDIT_FRAMES = new java.util.HashMap<>();
    private static final java.util.Set<Integer> AUDIT_AT = java.util.Set.of(1, 60, 200);

    /**
     * Says, once per model, where Epic Fight's animation actually put every
     * joint of the converted skeleton.
     */
    private static float round(float value) {
        return Math.round(value * 100.0F) / 100.0F;
    }

    private static void auditPose(String id, Armature armature) {
        int frame = AUDIT_FRAMES.merge(id, 1, Integer::sum);

        if (!AUDIT_AT.contains(frame)) {
            return;
        }

        try {
            OpenMatrix4f[] poses = armature.getPoseMatrices();
            StringBuilder odd = new StringBuilder();
            float lowest = Float.MAX_VALUE;
            float highest = -Float.MAX_VALUE;
            float widest = 0.0F;
            float bindHighest = -Float.MAX_VALUE;
            String headBind = "?";
            String headPosed = "?";

            for (Joint joint : armature.rootJoint.getAllJoints()) {
                int jointId = joint.getId();

                if (jointId < 0 || jointId >= poses.length || poses[jointId] == null) {
                    continue;
                }

                OpenMatrix4f bind = OpenMatrix4f.invert(joint.getToOrigin(), null);

                // Epic Fight fills this array with plain animated world
                // matrices - it calls getPoseTransform with applyToOrigin
                OpenMatrix4f posed = poses[jointId];
                bindHighest = Math.max(bindHighest, bind.m31);

                // The skeleton the model was built with against the one Epic
                // Fight hands back: if the second is about the first plus a
                if ("Head".equals(joint.getName())) {
                    headBind = round(bind.m30) + ", " + round(bind.m31) + ", " + round(bind.m32);
                    headPosed = round(posed.m30) + ", " + round(posed.m31) + ", " + round(posed.m32);
                }
                float x = posed.m30;
                float y = posed.m31;
                float z = posed.m32;
                lowest = Math.min(lowest, y);
                highest = Math.max(highest, y);
                widest = Math.max(widest, Math.max(Math.abs(x), Math.abs(z)));

                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                        || Math.abs(x) > 2.5F || Math.abs(z) > 2.5F || y < -1.5F || y > 4.0F) {
                    odd.append(odd.length() == 0 ? "" : ", ")
                            .append(joint.getName()).append(" (")
                            .append(Math.round(x * 100.0F) / 100.0F).append(", ")
                            .append(Math.round(y * 100.0F) / 100.0F).append(", ")
                            .append(Math.round(z * 100.0F) / 100.0F).append(")");
                }
            }

            com.argorice.epicysm.client.Diag.info("Model {} at frame {}: built {} blocks tall, posed {} to {} tall and {} out to the"
                    + " side; head sits at ({}) unposed and ({}) posed", id, frame, round(bindHighest), round(lowest),
                    round(highest), round(widest), headBind, headPosed);

            if (odd.length() > 0) {
                EpicYsm.LOGGER.warn("Model {}: Epic Fight's pose put these joints somewhere a body cannot be: {}",
                        id, odd);
            }
        } catch (Throwable t) {
            com.argorice.epicysm.client.Diag.info("Model {}: could not check the posed skeleton", id, t);
        }
    }

    /**
     * Same part-visibility rules vanilla PlayerRenderer.setModelProperties
     * applies, read straight from the player's skin customization (that
     * method is private without Epic Fight's access transformer).
     */
    private void prepareMeshParts(HumanoidMesh mesh, AbstractClientPlayer entity) {
        mesh.initialize();

        boolean spectator = entity.isSpectator();
        mesh.head.setHidden(false);
        mesh.hat.setHidden(!entity.isModelPartShown(PlayerModelPart.HAT));
        mesh.torso.setHidden(spectator);
        mesh.jacket.setHidden(spectator || !entity.isModelPartShown(PlayerModelPart.JACKET));
        mesh.leftArm.setHidden(spectator);
        mesh.rightArm.setHidden(spectator);
        mesh.leftSleeve.setHidden(spectator || !entity.isModelPartShown(PlayerModelPart.LEFT_SLEEVE));
        mesh.rightSleeve.setHidden(spectator || !entity.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE));
        mesh.leftLeg.setHidden(spectator);
        mesh.rightLeg.setHidden(spectator);
        mesh.leftPants.setHidden(spectator || !entity.isModelPartShown(PlayerModelPart.LEFT_PANTS_LEG));
        mesh.rightPants.setHidden(spectator || !entity.isModelPartShown(PlayerModelPart.RIGHT_PANTS_LEG));
    }

    @SuppressWarnings("unchecked")
    private static LivingEntityRenderer<AbstractClientPlayer, HumanoidModel<AbstractClientPlayer>> castRenderer(PlayerRenderer playerRenderer) {
        return (LivingEntityRenderer<AbstractClientPlayer, HumanoidModel<AbstractClientPlayer>>) (LivingEntityRenderer<?, ?>) playerRenderer;
    }

    /** The vanilla player renderer for this player's skin type. */
    @javax.annotation.Nullable
    private static boolean itemLayerReported;

    /** Says, once, whether the item in the player's hand can be drawn at all. */
    private static void reportItemLayer(PlayerRenderer vanilla, Object renderer, Armature armature) {
        if (itemLayerReported) {
            return;
        }

        itemLayerReported = true;

        try {
            String owner;
            java.util.List<String> layers = new java.util.ArrayList<>();
            Object source = vanilla != null ? vanilla : renderer;

            if (source instanceof LivingEntityRenderer<?, ?> living) {
                owner = vanilla != null ? "the vanilla player renderer" : "the renderer another mod supplied";

                for (Object layer : layersOf(living)) {
                    layers.add(layer.getClass().getSimpleName());
                }
            } else {
                owner = "nothing that has layers";
            }

            boolean holdsItem = false;

            for (String layer : layers) {
                holdsItem |= layer.contains("ItemInHand");
            }

            OpenMatrix4f[] pose = armature.getPoseMatrices();
            int tool = com.argorice.epicysm.client.convert.JointMapper.jointId("Tool_R");
            String where = pose != null && tool < pose.length
                    ? String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)",
                            pose[tool].m30, pose[tool].m31, pose[tool].m32)
                    : "nowhere - the pose has only " + (pose == null ? 0 : pose.length) + " joint(s)";

            com.argorice.epicysm.client.Diag.info("Held item: the layers come from {} ({} of them{}); the joint that holds a"
                    + " weapon, Tool_R, is at {}. Without an item layer here nothing draws what the player"
                    + " is holding. Layers: {}", owner, layers.size(),
                    holdsItem ? ", one of which draws the item in hand" : " and NONE of them draws the item in hand",
                    where, layers);
        } catch (Throwable t) {
            com.argorice.epicysm.client.Diag.info("Held item: could not tell which layers this player is drawn with", t);
        }
    }

    /** The list of layers a renderer draws after its model, whatever it is. */
    private static java.util.List<?> layersOf(LivingEntityRenderer<?, ?> renderer) {
        for (Class<?> type = renderer.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!java.util.List.class.isAssignableFrom(field.getType())) {
                    continue;
                }

                try {
                    if (field.trySetAccessible() && field.get(renderer) instanceof java.util.List<?> found) {
                        return found;
                    }
                } catch (Throwable ignored) {
                    // Not this one.
                }
            }
        }

        return java.util.List.of();
    }

    /** The vanilla player renderer, borrowed for its layers. */
    private static PlayerRenderer vanillaPlayerRenderer(AbstractClientPlayer entity) {
        try {
            java.util.Map<?, ?> skinMap = Minecraft.getInstance().getEntityRenderDispatcher().getSkinMap();
            Object candidate = skinMap.get(entity.getSkin().model());

            if (!(candidate instanceof PlayerRenderer)) {
                for (Object value : skinMap.values()) {
                    if (value instanceof PlayerRenderer) {
                        candidate = value;
                        break;
                    }
                }
            }

            return candidate instanceof PlayerRenderer playerRenderer ? playerRenderer : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private RenderType pickRenderType(ConvertedModel model, boolean isVisible, boolean isVisibleToPlayer, boolean isGlowing) {
        // Same visibility semantics as LivingEntityRenderer.getRenderType,
        // but with the converted model's texture.
        if (isVisible) {
            return model.translucent() ? RenderType.entityTranslucent(model.texture()) : RenderType.entityCutoutNoCull(model.texture());
        } else if (isVisibleToPlayer) {
            return RenderType.itemEntityTranslucentCull(model.texture());
        } else if (isGlowing) {
            return RenderType.outline(model.texture());
        }

        return null;
    }

    private RenderType pickSkinRenderType(ResourceLocation skin, boolean isVisible, boolean isVisibleToPlayer, boolean isGlowing) {
        // Same visibility semantics as LivingEntityRenderer.getRenderType,
        // with the player's own skin texture (used when a foreign renderer
        // holds the player but no converted model is assigned).
        if (isVisible) {
            return RenderType.entityTranslucent(skin);
        } else if (isVisibleToPlayer) {
            return RenderType.itemEntityTranslucentCull(skin);
        } else if (isGlowing) {
            return RenderType.outline(skin);
        }

        return null;
    }

    @Override
    public AssetAccessor<HumanoidMesh> getMeshProvider(AbstractClientPlayerPatch<AbstractClientPlayer> entitypatch) {
        return PlayerSkin.Model.SLIM.equals(entitypatch.getOriginal().getSkin().model()) ? Meshes.ALEX : Meshes.BIPED;
    }

    @Override
    public AssetAccessor<HumanoidMesh> getDefaultMesh() {
        return Meshes.BIPED;
    }

    @Override
    protected float getDefaultLayerHeightCorrection() {
        return 0.75F;
    }
}
