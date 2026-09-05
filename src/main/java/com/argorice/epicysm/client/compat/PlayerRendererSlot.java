package com.argorice.epicysm.client.compat;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.EntityType;
import yesman.epicfight.client.ClientEngine;
import yesman.epicfight.client.renderer.patched.entity.PatchedEntityRenderer;

import com.argorice.epicysm.EpicYsm;
import com.argorice.epicysm.client.render.EpicYsmPlayerRenderer;

/**
 * Epic Fight has one slot for the player's renderer, and every mod that
 * wants to draw players differently puts its own renderer there. Whoever
 * registers last wins, and the others are simply never called.
 *
 * This mod would rather share the slot than win it: its renderer keeps
 * whatever was in the slot before it and hands the player over to that
 * renderer whenever the player is not its business. And if another mod's
 * renderer turns up in front of this one after all, it is wrapped the same
 * way, so both keep working.
 */
public final class PlayerRendererSlot {
    @Nullable
    private static EntityRendererProvider.Context context;
    private static int lastCheckTick = Integer.MIN_VALUE;
    private static boolean gaveUp;

    private PlayerRendererSlot() {
    }

    /** The renderer context Epic Fight handed out; needed to build a renderer later. */
    public static void rememberContext(EntityRendererProvider.Context rendererContext) {
        context = rendererContext;
    }

    /**
     * The player renderer provider registered before this mod's, read off
     * the registration event's own map. Null when there is none, or when
     * the event does not look like expected.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static Function<EntityType<?>, PatchedEntityRenderer> providerBefore(Object event) {
        try {
            for (Class<?> type = event.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!Map.class.isAssignableFrom(field.getType()) || !field.trySetAccessible()) {
                        continue;
                    }

                    Object value = field.get(event);

                    if (value instanceof Map<?, ?> map && map.get(EntityType.PLAYER) instanceof Function<?, ?> provider) {
                        return (Function<EntityType<?>, PatchedEntityRenderer>) provider;
                    }
                }
            }
        } catch (Throwable t) {
            EpicYsm.LOGGER.debug("Could not read the player renderer registered before this mod's", t);
        }

        return null;
    }

    /**
     * Every few seconds: is this mod's renderer the one Epic Fight uses for
     * players? If another mod's has taken the slot, it is wrapped so that
     * both are drawn through.
     */
    public static void ensureInFront(int tick) {
        if (gaveUp || context == null || tick - lastCheckTick < 100) {
            return;
        }

        lastCheckTick = tick;

        try {
            var engine = ClientEngine.getInstance().renderEngine;
            PatchedEntityRenderer current = engine.getEntityRenderer(EntityType.PLAYER);

            if (current == null || current instanceof EpicYsmPlayerRenderer) {
                return;
            }

            EpicYsmPlayerRenderer wrapper = new EpicYsmPlayerRenderer(context, EntityType.PLAYER, type -> current);
            wrapper.initLayerLast(context, EntityType.PLAYER);
            boolean placed = false;

            for (Field field : engine.getClass().getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType()) || !field.trySetAccessible()) {
                    continue;
                }

                Object value = field.get(engine);

                if (value instanceof Map<?, ?> map && map.get(EntityType.PLAYER) == current) {
                    @SuppressWarnings("unchecked")
                    Map<EntityType<?>, PatchedEntityRenderer> cache = (Map<EntityType<?>, PatchedEntityRenderer>) map;
                    cache.put(EntityType.PLAYER, wrapper);
                    placed = true;
                    break;
                }
            }

            if (placed) {
                EpicYsm.LOGGER.info("Another mod's player renderer ({}) sits in front of this mod's; players are now drawn"
                        + " through this mod, which hands them to that renderer whenever it owns their look",
                        current.getClass().getName());
            } else {
                gaveUp = true;
                EpicYsm.LOGGER.warn("Another mod's player renderer ({}) sits in front of this mod's, and Epic Fight's"
                        + " renderer table could not be reached to share the slot; Yes Steve Model models will not"
                        + " fight until that mod is removed or loads before this one", current.getClass().getName());
            }
        } catch (Throwable t) {
            gaveUp = true;
            EpicYsm.LOGGER.warn("Could not check which player renderer Epic Fight uses", t);
        }
    }
}
