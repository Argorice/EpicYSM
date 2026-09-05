package com.argorice.epicysm.client.compat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import net.minecraft.client.player.AbstractClientPlayer;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.gameasset.Armatures;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import com.argorice.epicysm.EpicYsm;

/**
 * Whether another mod owns how a player looks right now - a transformation
 * into a demon, a possession, a costume the mod draws itself. For as long
 * as it does, this mod steps aside for that player: no Yes Steve Model, no
 * converted mesh, the other mod's renderer draws what it wants to draw. The
 * moment the look is given back, Yes Steve Model is back too.
 *
 * Two ways to notice. The general one: a mod that changes a player into
 * something else gives the player a skeleton of its own, and the patch's
 * armature is no longer Epic Fight's biped. The specific ones: detectors
 * registered for mods that keep the biped skeleton and swap the mesh, one
 * per mod, each looking at whatever that mod exposes.
 */
public final class LookOwners {
    /** One mod's way of saying "this player is mine right now". */
    public interface Detector {
        /** A short name for the log, e.g. the mod's id. */
        String name();

        /** Null when the mod does not own the look; a word on why when it does. */
        @Nullable
        String owns(AbstractClientPlayer player, LivingEntityPatch<?> patch);

        /**
         * Null when the mod is not hiding the player; a word on why when it
         * is - a teleport, a cut where the character is meant to vanish.
         * While a mod hides a player, nothing draws that player, this mod
         * and Yes Steve Model included.
         */
        @Nullable
        default String hides(AbstractClientPlayer player, LivingEntityPatch<?> patch) {
            return null;
        }
    }

    private static final List<Detector> DETECTORS = new CopyOnWriteArrayList<>();

    /** Per player: the tick the answer was found on, and the answer. */
    private static final Map<UUID, Object[]> ANSWERS = new HashMap<>();
    private static final Map<UUID, Object[]> HIDDEN = new HashMap<>();
    private static final Map<UUID, String> SAID = new HashMap<>();
    private static String bipedName;

    private LookOwners() {
    }

    /** The detectors this mod ships, for the mods it knows; each registers itself only when its mod is there. */
    public static void registerBuiltIn() {
        InvincibleDmc.registerIfPresent();
    }

    public static void register(Detector detector) {
        DETECTORS.add(detector);
        EpicYsm.LOGGER.info("Compatibility: {} may take over a player's look; Yes Steve Model steps aside while it does", detector.name());
    }

    /** True while another mod owns this player's look. Cheap to call often; answered once a tick. */
    public static boolean ownsLook(AbstractClientPlayer player) {
        return reason(player) != null;
    }

    /** Why another mod owns this player's look, or null. */
    @Nullable
    public static String reason(AbstractClientPlayer player) {
        UUID id = player.getUUID();
        Object[] answer = ANSWERS.get(id);

        if (answer != null && (Integer) answer[0] == player.tickCount) {
            return (String) answer[1];
        }

        String reason = null;

        try {
            reason = look(player);
        } catch (Throwable t) {
            // A detector that fails says nothing.
        }

        ANSWERS.put(id, new Object[] { player.tickCount, reason });
        String before = SAID.get(id);

        if (reason != null && !reason.equals(before)) {
            EpicYsm.LOGGER.info("Player {}: another mod has taken over the look ({}); Yes Steve Model steps aside until it is given back",
                    player.getGameProfile().getName(), reason);
            SAID.put(id, reason);
        } else if (reason == null && before != null) {
            EpicYsm.LOGGER.info("Player {}: the look is given back; Yes Steve Model draws again", player.getGameProfile().getName());
            SAID.remove(id);
        }

        return reason;
    }

    /**
     * True while a detector wants this player not drawn at all. Unlike the
     * look, this changes within an animation, so it is asked every frame.
     * The renderer is whoever is about to draw the player, for the log; a
     * string names the caller instead.
     */
    public static boolean hidden(AbstractClientPlayer player, @Nullable Object renderer) {
        if (DETECTORS.isEmpty()) {
            return false;
        }

        try {
            LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(player, LivingEntityPatch.class);

            if (patch == null) {
                return false;
            }

            for (Detector detector : DETECTORS) {
                String why = detector.hides(player, patch);

                if (why != null) {
                    hiddenRender(player, detector.name() + " (" + why + ")", renderer);
                    return true;
                }
            }
        } catch (Throwable t) {
            // A detector that fails hides nothing.
        }

        return false;
    }

    /**
     * Another mod cancelled this player's render before anyone drew - the
     * general way a mod hides a player for a moment, whatever the reason.
     * Nothing else draws the player either, Yes Steve Model included.
     */
    public static void hiddenByAnotherMod(AbstractClientPlayer player, @Nullable Object renderer) {
        hiddenRender(player, "another mod, which cancelled the render", renderer);
    }

    /** The animation itself shrinks the body to nothing this frame - the way a character is made to vanish. */
    public static void hiddenByAnimation(AbstractClientPlayer player, @Nullable Object renderer) {
        hiddenRender(player, "the animation, which shrinks the body to nothing", renderer);
    }

    /** A render of this player that went ahead: whatever hid the player is over. */
    public static void shown(AbstractClientPlayer player) {
        Object[] episode = HIDDEN.remove(player.getUUID());

        if (episode != null) {
            EpicYsm.LOGGER.info("Player {}: shown again after {} hidden render(s)", player.getGameProfile().getName(),
                    (Integer) episode[1] + 1);
        }
    }

    /**
     * True while the player is hidden by whatever means - a detector saying
     * so now, or a render of the player cancelled by another mod within the
     * last few frames. For code that has no render event of its own to look
     * at, such as the gate on Yes Steve Model's drawing.
     */
    public static boolean hiddenLately(AbstractClientPlayer player, String askedBy) {
        if (hidden(player, askedBy)) {
            return true;
        }

        Object[] episode = HIDDEN.get(player.getUUID());
        return episode != null && System.nanoTime() - (Long) episode[2] < RECENT_NANOS;
    }

    /** How long a cancelled render keeps counting as "hidden now": a few frames. */
    private static final long RECENT_NANOS = 100_000_000L;

    private static void hiddenRender(AbstractClientPlayer player, String by, @Nullable Object renderer) {
        Object[] episode = HIDDEN.get(player.getUUID());

        if (episode == null) {
            HIDDEN.put(player.getUUID(), new Object[] { by, 0, System.nanoTime() });
            EpicYsm.LOGGER.info("Player {}: hidden by {}; nothing draws the player until it is over. The render was"
                    + " asked for by {}", player.getGameProfile().getName(), by,
                    renderer == null ? "nobody" : renderer instanceof String name ? name : renderer.getClass().getName());
        } else {
            episode[1] = (Integer) episode[1] + 1;
            episode[2] = System.nanoTime();
        }
    }

    @Nullable
    private static String look(AbstractClientPlayer player) {
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(player, LivingEntityPatch.class);

        if (patch == null) {
            return null;
        }

        Armature armature = patch.getArmature();

        if (armature != null && !isBiped(armature)) {
            return "skeleton " + armature;
        }

        for (Detector detector : DETECTORS) {
            String why = detector.owns(player, patch);

            if (why != null) {
                return detector.name() + ": " + why;
            }
        }

        return null;
    }

    /** Epic Fight's own player skeleton, by name, so that a copy of it counts too. */
    private static boolean isBiped(Armature armature) {
        if (bipedName == null) {
            try {
                bipedName = String.valueOf(Armatures.BIPED.get());
            } catch (Throwable t) {
                bipedName = "epicfight:biped";
            }
        }

        return bipedName.equals(String.valueOf(armature));
    }

    public static void forget(UUID player) {
        ANSWERS.remove(player);
        SAID.remove(player);
        HIDDEN.remove(player);
    }

    public static void resetAll() {
        ANSWERS.clear();
        SAID.clear();
        HIDDEN.clear();
    }
}
