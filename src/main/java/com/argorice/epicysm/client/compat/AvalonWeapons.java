package com.argorice.epicysm.client.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.item.RenderItemBase;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import com.argorice.epicysm.EpicYsm;

/**
 * Weapons that come as a mesh of their own, skinned to joints of their own
 * - Epic Fight: Nightfall's thorn wheel and its claws, built on Avalon.
 * Such a weapon gives the player Epic Fight's biped with the weapon's
 * joints added, and hangs its mesh from those joints and from the hand.
 * Epic Fight's own renderer draws the mesh at the end of its layers, at
 * the biped's proportions; the renderer the weapon registers for the hand
 * draws nothing there. On an encrypted model, which Yes Steve Model draws,
 * those layers never run, so the weapon was not on screen at all.
 *
 * So the mesh is drawn here, at this model's joints: every joint the
 * solver placed is used where it put it, and a joint of the weapon's own
 * is placed where the animation holds it relative to the nearest joint
 * above it that the solver did place. Avalon is optional and reached by
 * reflection; its renderer keeps the mesh and the two textures in public
 * fields, and its item answers with the weapon's skeleton.
 */
public final class AvalonWeapons {
    private static final Object NONE = new Object();

    /** Per renderer class: the mesh, texture and light-texture fields, or NONE. */
    private static final Map<Class<?>, Object> RENDERERS = new HashMap<>();
    /** Per item class: the getArmature method, or NONE. */
    private static final Map<Class<?>, Object> ITEMS = new HashMap<>();
    private static boolean failed;
    private static boolean said;

    private AvalonWeapons() {
    }

    /**
     * Draws the weapon's mesh at this model's joints and returns true, or
     * returns false when the item is not such a weapon. The joints are the
     * solved world matrices by name, in blocks; size scales them to the
     * units the pose stack is in, as the held item's are scaled.
     */
    public static boolean draw(RenderItemBase renderer, ItemStack stack, LivingEntityPatch<?> patch,
                               Map<String, Matrix4f> drawn, float size, MultiBufferSource buffers,
                               PoseStack poseStack, int light, float partialTicks) {
        if (failed || renderer == null || stack.isEmpty()) {
            return false;
        }

        try {
            Field[] fields = rendererFields(renderer.getClass());

            if (fields == null) {
                return false;
            }

            Armature armature = weaponArmature(stack);

            if (armature == null || armature.rootJoint == null) {
                return false;
            }

            Object meshAccessor = fields[0].get(renderer);
            Object mesh = meshAccessor instanceof AssetAccessor<?> accessor ? accessor.get() : null;
            Object texture = fields[1].get(renderer);
            Object lightTexture = fields[2].get(renderer);

            if (!(mesh instanceof SkinnedMesh skinned) || !(texture instanceof ResourceLocation textureLocation)) {
                return false;
            }

            Pose pose = patch.getAnimator() == null ? null : patch.getAnimator().getPose(partialTicks);
            OpenMatrix4f[] poses = place(armature, pose, drawn, size);

            if (poses == null) {
                return false;
            }

            if (!said) {
                said = true;
                EpicYsm.LOGGER.info("Weapons: {} is a mesh of its own on the skeleton {}; it is drawn at this model's"
                        + " joints, the weapon's own joints placed from the nearest joint above them", stack.getItem(),
                        armature);
            }

            skinned.draw(poseStack, buffers, RenderType.entityTranslucent(textureLocation), light, 1.0F, 1.0F, 1.0F, 1.0F,
                    OverlayTexture.NO_OVERLAY, armature, poses);

            if (lightTexture instanceof ResourceLocation lightLocation) {
                skinned.draw(poseStack, buffers, RenderType.entityTranslucentEmissive(lightLocation), light, 1.0F, 1.0F, 1.0F, 1.0F,
                        OverlayTexture.NO_OVERLAY, armature, poses);
            }

            return true;
        } catch (Throwable t) {
            failed = true;
            EpicYsm.LOGGER.warn("A weapon with a mesh of its own could not be drawn on an encrypted model; such weapons"
                    + " are left undrawn there from now on", t);
            return false;
        }
    }

    /**
     * The weapon skeleton's joints in Epic Fight's flat array: the solved
     * matrix for a joint the solver placed, and for any other the
     * animation's own hold on it relative to the nearest placed joint
     * above it. Null when nothing was placed at all.
     */
    @Nullable
    private static OpenMatrix4f[] place(Armature armature, @Nullable Pose pose, Map<String, Matrix4f> drawn, float size) {
        List<Joint> all = new ArrayList<>();
        Map<Joint, Joint> above = new HashMap<>();
        gather(armature.rootJoint, null, all, above);
        int most = 0;

        for (Joint joint : all) {
            most = Math.max(most, joint.getId() + 1);
        }

        if (most == 0) {
            return null;
        }

        Map<Joint, Matrix4f> placed = new HashMap<>();

        for (Joint joint : all) {
            Matrix4f found = drawn.get(joint.getName());

            if (found != null) {
                Matrix4f scaled = new Matrix4f(found);
                scaled.setTranslation(found.getTranslation(new Vector3f()).mul(size));
                placed.put(joint, scaled);
            }
        }

        if (placed.isEmpty()) {
            return null;
        }

        OpenMatrix4f[] out = new OpenMatrix4f[most];

        for (int i = 0; i < most; i++) {
            out[i] = new OpenMatrix4f();
        }

        for (Joint joint : all) {
            Matrix4f ours = placed.get(joint);

            if (ours == null) {
                Joint anchor = above.get(joint);

                while (anchor != null && !placed.containsKey(anchor)) {
                    anchor = above.get(anchor);
                }

                if (anchor == null || pose == null) {
                    continue;
                }

                // Where the animation holds this joint, seen from the anchor.
                Matrix4f theirs = OpenMatrix4f.exportToMojangMatrix(armature.getBoundTransformFor(pose, joint));
                Matrix4f theirAnchor = OpenMatrix4f.exportToMojangMatrix(armature.getBoundTransformFor(pose, anchor));
                Matrix4f relative = theirAnchor.invert().mul(theirs);
                ours = new Matrix4f(placed.get(anchor)).mul(relative);
                placed.put(joint, ours);
            }

            out[joint.getId()] = OpenMatrix4f.importFromMojangMatrix(ours);
        }

        return out;
    }

    private static void gather(Joint joint, @Nullable Joint parent, List<Joint> all, Map<Joint, Joint> above) {
        all.add(joint);

        if (parent != null) {
            above.put(joint, parent);
        }

        for (Joint child : joint.getSubJoints()) {
            gather(child, joint, all, above);
        }
    }

    /** The mesh, texture and light-texture fields of one of Avalon's animation-item renderers, or null. */
    @Nullable
    private static Field[] rendererFields(Class<?> type) {
        Object known = RENDERERS.get(type);

        if (known == null) {
            Field mesh = publicField(type, "mesh", AssetAccessor.class);
            Field texture = publicField(type, "texture", ResourceLocation.class);
            Field lightTexture = publicField(type, "texture_l", ResourceLocation.class);
            known = mesh != null && texture != null && lightTexture != null ? new Field[] { mesh, texture, lightTexture } : NONE;
            RENDERERS.put(type, known);
        }

        return known instanceof Field[] fields ? fields : null;
    }

    @Nullable
    private static Field publicField(Class<?> type, String name, Class<?> expected) {
        try {
            Field field = type.getField(name);
            return expected.isAssignableFrom(field.getType()) ? field : null;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /** The skeleton the item gives the player, from its getArmature(), or null when it gives none. */
    @Nullable
    private static Armature weaponArmature(ItemStack stack) throws ReflectiveOperationException {
        Class<?> type = stack.getItem().getClass();
        Object known = ITEMS.get(type);

        if (known == null) {
            Method getter = null;

            try {
                Method found = type.getMethod("getArmature");

                if (AssetAccessor.class.isAssignableFrom(found.getReturnType())) {
                    getter = found;
                }
            } catch (NoSuchMethodException ignored) {
            }

            known = getter != null ? getter : NONE;
            ITEMS.put(type, known);
        }

        if (!(known instanceof Method getter)) {
            return null;
        }

        Object accessor = getter.invoke(stack.getItem());
        Object armature = accessor instanceof AssetAccessor<?> asset ? asset.get() : null;
        return armature instanceof Armature found ? found : null;
    }
}
