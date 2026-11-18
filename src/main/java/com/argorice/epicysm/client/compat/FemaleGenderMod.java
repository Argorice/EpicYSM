package com.argorice.epicysm.client.compat;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.argorice.epicysm.EpicYsm;

/**
 * Wildfire's Female Gender Mod (wildfire_gender). It adds a layer of its
 * own to the player's renderer, drawn against the vanilla body's chest.
 *
 * Yes Steve Model never draws that layer on its models: it draws them
 * through a renderer of its own, which has not got the layer. Epic Fight
 * does draw it, through the vanilla body it keeps posed for the layers it
 * has no counterpart of its own for, hung on the root joint - and on a
 * converted model that is a chest for a body which is not there, drawn
 * beside the model. So on a converted model the layer is left out, the way
 * Yes Steve Model leaves it out.
 */
public final class FemaleGenderMod {
    private FemaleGenderMod() {
    }

    private static final Set<Class<?>> SAID = Collections.newSetFromMap(new HashMap<>());

    /** Whether this vanilla layer class is the gender mod's. */
    public static boolean isLayer(Class<?> type) {
        String name = type.getName();
        return name.startsWith("com.wildfire.render.") || type.getSimpleName().contains("GenderLayer");
    }

    /**
     * Takes the gender mod's entries out of a renderer's table of patched
     * layers for the length of a draw. Returns what was taken out, to be
     * put back with {@link #restore}, or null when there was nothing.
     */
    @Nullable
    public static <V> Map<Class<?>, V> setAside(Map<Class<?>, V> patchedLayers) {
        Map<Class<?>, V> aside = null;

        for (Iterator<Map.Entry<Class<?>, V>> it = patchedLayers.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Class<?>, V> entry = it.next();

            if (!isLayer(entry.getKey())) {
                continue;
            }

            if (aside == null) {
                aside = new HashMap<>();
            }

            aside.put(entry.getKey(), entry.getValue());
            it.remove();

            if (SAID.add(entry.getKey())) {
                EpicYsm.LOGGER.info("Compatibility: Wildfire's Female Gender Mod adds a layer to the player ({}); Epic Fight"
                        + " draws it against the vanilla body, which a Yes Steve Model model has not got, so on a converted"
                        + " model it is left out, as Yes Steve Model leaves it out itself", entry.getKey().getName());
            }
        }

        return aside;
    }

    public static <V> void restore(Map<Class<?>, V> patchedLayers, @Nullable Map<Class<?>, V> aside) {
        if (aside != null) {
            patchedLayers.putAll(aside);
        }
    }
}
