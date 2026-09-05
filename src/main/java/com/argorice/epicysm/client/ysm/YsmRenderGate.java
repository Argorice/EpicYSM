package com.argorice.epicysm.client.ysm;

import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.NeoForge;

import com.argorice.epicysm.EpicYsm;
import com.argorice.epicysm.client.compat.LookOwners;

/**
 * Yes Steve Model asks, right before it draws a player - in the world, or
 * the player's own body seen from the first person - whether it may, by
 * posting an event of its own that anyone can cancel. That is the one door
 * every one of its drawing paths goes through, so it is where a hidden
 * player is kept from being drawn: the render event a hiding mod cancels
 * only covers the path through the entity renderer.
 *
 * The event's class has no stable name (the jar is obfuscated), so it is
 * found by its shape instead: a cancellable event of Yes Steve Model's
 * made with a player, its model and the model's id.
 */
public final class YsmRenderGate {
    private static boolean installed;

    private YsmRenderGate() {
    }

    public static void install() {
        if (installed) {
            return;
        }

        installed = true;

        try {
            for (String name : YsmClasses.extending(Event.class.getName())) {
                Class<?> type = Class.forName(name, false, YsmRenderGate.class.getClassLoader());

                if (!looksLikeRenderEvent(type)) {
                    continue;
                }

                Method playerOf = playerAccessor(type);

                if (playerOf == null) {
                    continue;
                }

                listen(type.asSubclass(Event.class), playerOf);
                EpicYsm.LOGGER.info("Yes Steve Model asks before drawing a player ({}); hidden players are refused there",
                        name);
                return;
            }

            EpicYsm.LOGGER.info("Yes Steve Model's own render event was not found; a hidden player is kept from being drawn"
                    + " through the entity render event only");
        } catch (Throwable t) {
            EpicYsm.LOGGER.warn("Could not listen to Yes Steve Model's own render event", t);
        }
    }

    private static <T extends Event> void listen(Class<T> type, Method playerOf) {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, type, event -> {
            try {
                if (event instanceof ICancellableEvent cancellable
                        && playerOf.invoke(event) instanceof AbstractClientPlayer player
                        && LookOwners.hiddenLately(player, "Yes Steve Model's own render event")) {
                    cancellable.setCanceled(true);
                }
            } catch (Throwable t) {
                // Nothing to say; the player is drawn.
            }
        });
    }

    /** A cancellable event built from a player, a model and the model's id. */
    private static boolean looksLikeRenderEvent(Class<?> type) {
        if (!ICancellableEvent.class.isAssignableFrom(type)) {
            return false;
        }

        for (var constructor : type.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();

            if (parameters.length == 3 && Player.class.isAssignableFrom(parameters[0]) && parameters[2] == String.class) {
                return true;
            }
        }

        return false;
    }

    private static Method playerAccessor(Class<?> type) {
        List<Method> found = new java.util.ArrayList<>();

        for (Method method : type.getMethods()) {
            if (method.getParameterCount() == 0 && Player.class.isAssignableFrom(method.getReturnType())) {
                found.add(method);
            }
        }

        return found.size() == 1 ? found.get(0) : null;
    }
}
