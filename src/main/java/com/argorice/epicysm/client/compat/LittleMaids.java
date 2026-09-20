package com.argorice.epicysm.client.compat;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.world.entity.LivingEntity;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import com.argorice.epicysm.EpicYsm;

/**
 * Maids of Touhou Little Maid wearing a Yes Steve Model model, fighting
 * through Epic Fight: Touhou Little Maid.
 *
 * A maid may be dressed in any Yes Steve Model model; Yes Steve Model then
 * draws her as it draws a player, with its own renderer for maids. Epic
 * Fight: Touhou Little Maid gives her an Epic Fight patch and, while she
 * is set to fight, has Epic Fight draw her instead - with the add-on's own
 * fox-maid mesh, since it has no mesh for a Yes Steve Model model, and
 * with whatever texture her renderer names, which is the Yes Steve Model
 * one. What was on screen was the fox with another model's picture on it.
 *
 * Such a maid is taken here the way a player in an encrypted model is:
 * Yes Steve Model's own renderer draws her, Epic Fight's pose is written
 * into her bones a moment before, and Epic Fight draws the weapon in her
 * hand. The add-on's renderer never sees her; every other maid is left to
 * it. Nothing of Touhou Little Maid is needed at compile time: the maid
 * says whether she wears a Yes Steve Model model through two plain
 * methods of hers, found by name.
 */
public final class LittleMaids {
    private static final String MAID = "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid";

    @Nullable
    private static Class<?> maidClass;
    @Nullable
    private static Method isYsmModel;
    @Nullable
    private static Method ysmModelId;
    private static boolean looked;
    private static boolean said;
    private static final Set<String> saidFor = new HashSet<>();

    private LittleMaids() {
    }

    /**
     * Whether Epic Fight is about to draw this maid in a Yes Steve Model
     * model: a maid, dressed by Yes Steve Model, with a patch that has
     * Epic Fight draw her right now (her fighting task). Cheap when the
     * mod is not there.
     */
    public static boolean fightingInYsmModel(LivingEntity entity) {
        if (!isMaid(entity) || !wearsYsmModel(entity)) {
            return false;
        }

        try {
            LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(entity, LivingEntityPatch.class);

            if (patch == null || !patch.overrideRender()) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }

        if (!said) {
            said = true;
            EpicYsm.LOGGER.info("Compatibility: a maid of Touhou Little Maid wearing a Yes Steve Model model is fighting"
                    + " through Epic Fight; she is drawn by Yes Steve Model with Epic Fight's pose, as a player in"
                    + " an encrypted model is, instead of by the add-on's own mesh");
        }

        String name = modelIdOf(entity);

        if (name != null && saidFor.add(name)) {
            com.argorice.epicysm.client.Diag.info("Maid {} fights in the Yes Steve Model model '{}'", entity.getName().getString(), name);
        }

        return true;
    }

    /**
     * The texture Yes Steve Model draws this maid with. Touhou Little
     * Maid's own renderer for maids keeps Yes Steve Model's renderer
     * inside it and hands a Yes Steve Model maid over to that; the texture
     * that names the model is the inner renderer's, the outer one names
     * the maid's ordinary model. Found once per renderer class.
     */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static net.minecraft.resources.ResourceLocation textureOf(Object renderer, LivingEntity maid) {
        Object inner = ysmRendererIn(renderer);

        try {
            if (inner instanceof net.minecraft.client.renderer.entity.EntityRenderer innerRenderer) {
                net.minecraft.resources.ResourceLocation texture = innerRenderer.getTextureLocation(maid);

                if (texture != null) {
                    return texture;
                }
            }
        } catch (Throwable ignored) {
            // The outer renderer's texture is the fallback.
        }

        try {
            return ((net.minecraft.client.renderer.entity.EntityRenderer) renderer).getTextureLocation(maid);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final java.util.Map<Class<?>, java.util.Optional<java.lang.reflect.Field>> YSM_RENDERER_FIELDS = new java.util.HashMap<>();

    @Nullable
    private static Object ysmRendererIn(Object renderer) {
        if (renderer == null) {
            return null;
        }

        java.util.Optional<java.lang.reflect.Field> field = YSM_RENDERER_FIELDS.computeIfAbsent(renderer.getClass(), type -> {
            for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
                for (java.lang.reflect.Field candidate : at.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(candidate.getModifiers()) || candidate.getType().isPrimitive()) {
                        continue;
                    }

                    try {
                        if (!candidate.trySetAccessible()) {
                            continue;
                        }

                        Object value = candidate.get(renderer);

                        if (value instanceof net.minecraft.client.renderer.entity.EntityRenderer
                                && value.getClass().getName().startsWith("com.elfmcys.")) {
                            return java.util.Optional.of(candidate);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            return java.util.Optional.empty();
        });

        try {
            return field.isPresent() ? field.get().get(renderer) : null;
        } catch (Throwable t) {
            return null;
        }
    }


    private static final java.util.Map<Class<?>, java.util.Optional<Method>> DRAW_ENTRIES = new java.util.HashMap<>();
    private static boolean saidEntry;

    /**
     * The entry through which Touhou Little Maid has Yes Steve Model draw
     * a maid, on this renderer - or null when it has none.
     *
     * The renderer Yes Steve Model posts the render event from, for a
     * maid, is a vanilla living-entity renderer underneath, with a plain
     * player model in it, and its render() is never what draws her:
     * Touhou Little Maid calls the add-on's entry point, geoRender, and
     * Yes Steve Model draws its model from there. Called on render(), the
     * renderer drew the plain player body in the model's texture - a dark
     * block with the picture's few opaque patches on it.
     */
    @Nullable
    public static Method drawEntryOf(Object renderer) {
        if (renderer == null) {
            return null;
        }

        return DRAW_ENTRIES.computeIfAbsent(renderer.getClass(), type -> {
            try {
                Method entry = type.getMethod("geoRender", net.minecraft.world.entity.Entity.class, float.class, float.class,
                        com.mojang.blaze3d.vertex.PoseStack.class, net.minecraft.client.renderer.MultiBufferSource.class,
                        int.class);
                return java.util.Optional.of(entry);
            } catch (NoSuchMethodException | SecurityException e) {
                return java.util.Optional.empty();
            }
        }).orElse(null);
    }

    /** Draws the maid through that entry. Whatever it throws is thrown on, unwrapped. */
    public static void draw(Method entry, Object renderer, LivingEntity maid, float yaw, float partialTicks,
                            com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource buffers,
                            int light) throws Throwable {
        if (!saidEntry) {
            saidEntry = true;
            EpicYsm.LOGGER.info("Compatibility: maids in Yes Steve Model models are drawn through Yes Steve Model's own entry for"
                    + " maids ({}.geoRender), the one Touhou Little Maid uses; the renderer's plain render() draws a"
                    + " vanilla body instead", renderer.getClass().getName());
        }

        try {
            entry.invoke(renderer, maid, yaw, partialTicks, poseStack, buffers, light);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        }
    }

    /** Whether this entity is a maid of Touhou Little Maid at all. */
    public static boolean isMaid(LivingEntity entity) {
        look();
        return maidClass != null && maidClass.isInstance(entity);
    }

    private static boolean wearsYsmModel(LivingEntity entity) {
        if (isYsmModel == null) {
            return false;
        }

        try {
            return Boolean.TRUE.equals(isYsmModel.invoke(entity));
        } catch (Throwable t) {
            return false;
        }
    }

    /** The Yes Steve Model model the maid wears, as Yes Steve Model names it, for the log. */
    @Nullable
    private static String modelIdOf(LivingEntity entity) {
        if (ysmModelId == null) {
            return null;
        }

        try {
            Object id = ysmModelId.invoke(entity);
            return id == null ? null : id.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void look() {
        if (looked) {
            return;
        }

        looked = true;

        try {
            Class<?> type = Class.forName(MAID, false, LittleMaids.class.getClassLoader());
            Method wears = type.getMethod("isYsmModel");

            if (wears.getReturnType() != boolean.class) {
                return;
            }

            maidClass = type;
            isYsmModel = wears;

            try {
                ysmModelId = type.getMethod("getYsmModelId");
            } catch (NoSuchMethodException ignored) {
                // The name is only for the log.
            }
        } catch (ClassNotFoundException ignored) {
            // Touhou Little Maid is not there.
        } catch (Throwable t) {
            EpicYsm.LOGGER.debug("Touhou Little Maid is there but its maid does not say whether she wears a Yes Steve Model"
                    + " model the way this mod expects; maids are left alone", t);
        }
    }
}
