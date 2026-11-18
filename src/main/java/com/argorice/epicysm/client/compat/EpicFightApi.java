package com.argorice.epicysm.client.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import javax.annotation.Nullable;

import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;

import com.argorice.epicysm.EpicYsm;

/**
 * The parts of Epic Fight this mod uses that one version of it has and
 * another has not, or has under another name. Each is looked up once, by
 * name, and asked through what was found; what is missing has a plain
 * answer that keeps the mod working as it did before the part came in.
 */
public final class EpicFightApi {
    @Nullable
    private static final MethodHandle MAIN_HAND_VALID = find(LivingEntityPatch.class, "isMainhandItemValid", boolean.class);

    /** "isEpicFightMode", called "isBattleMode" before Epic Fight 20.11. */
    @Nullable
    private static final MethodHandle EPIC_FIGHT_MODE = firstOf(find(PlayerPatch.class, "isEpicFightMode", boolean.class),
            find(PlayerPatch.class, "isBattleMode", boolean.class));

    private static final boolean DECORATIONS = classExists("yesman.epicfight.world.capabilities.entitypatch.EntityDecorations")
            && find(LivingEntityPatch.class, "getEntityDecorations", null) != null;

    private static boolean saidMissing;

    private EpicFightApi() {
    }

    /** Whether Epic Fight draws the item in the main hand: always, in the versions that do not say. */
    public static boolean mainhandItemValid(LivingEntityPatch<?> patch) {
        if (MAIN_HAND_VALID == null) {
            return true;
        }

        try {
            return (boolean) MAIN_HAND_VALID.invoke(patch);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Whether the player is in Epic Fight's battle mode. */
    public static boolean epicFightMode(PlayerPatch<?> patch) {
        if (EPIC_FIGHT_MODE == null) {
            sayMissing("whether a player is in battle mode");
            return false;
        }

        try {
            return (boolean) EPIC_FIGHT_MODE.invoke(patch);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether Epic Fight tints and lights a body through decorations of its patch (from Epic Fight 20.12 on). */
    public static boolean decorations() {
        return DECORATIONS;
    }

    /** Whether a class of Epic Fight's is there, without loading anything else of it. */
    public static boolean classExists(String name) {
        try {
            Class.forName(name, false, EpicFightApi.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** A public method taking nothing; any return type when it is given as null. */
    @Nullable
    private static MethodHandle find(Class<?> owner, String name, @Nullable Class<?> returns) {
        try {
            for (java.lang.reflect.Method method : owner.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0
                        && (returns == null || method.getReturnType() == returns)) {
                    return MethodHandles.publicLookup().unreflect(method)
                            .asType(MethodType.methodType(method.getReturnType(), owner));
                }
            }
        } catch (Throwable t) {
            // Not there in this version.
        }

        return null;
    }

    @Nullable
    private static MethodHandle firstOf(@Nullable MethodHandle first, @Nullable MethodHandle second) {
        return first != null ? first : second;
    }

    private static void sayMissing(String what) {
        if (!saidMissing) {
            saidMissing = true;
            EpicYsm.LOGGER.warn("Epic Fight: this version does not say {}; this mod leaves that part alone", what);
        }
    }
}
