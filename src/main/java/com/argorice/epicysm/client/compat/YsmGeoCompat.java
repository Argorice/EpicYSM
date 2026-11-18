package com.argorice.epicysm.client.compat;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import com.argorice.epicysm.EpicYsm;

/**
 * YSM GEO Compat, a mod that comes inside Epic Fight: Touhou Little Maid.
 * It turns the models in Yes Steve Model's folder into Epic Fight meshes of
 * its own, and draws a player in battle with the mesh of whichever model
 * the player's Yes Steve Model data names - through a renderer of its own
 * in Epic Fight's player slot, and through Epic Fight's own renderer, whose
 * choice of mesh it replaces.
 *
 * It asks which model is chosen, not whether Yes Steve Model is showing
 * it: a player who had switched the model off was a plain player out of
 * battle and the model again in it. Only for the models it can read, which
 * are the ones that are not encrypted - so a switched-off model came back
 * in battle if it was one of the open ones, and stayed off if it was not.
 *
 * A player Yes Steve Model is not drawing is therefore not handed to the
 * renderer behind this mod's while that mod is installed; this mod draws
 * the plain Epic Fight player itself.
 */
public final class YsmGeoCompat {
    private static Boolean present;
    private static boolean said;

    private YsmGeoCompat() {
    }

    /** Whether that mod is installed. Looked up by name once; nothing of it is needed. */
    public static boolean present() {
        if (present == null) {
            try {
                Class.forName("com.ysmef.geomodel.renderer.YSMMeshSelector", false, YsmGeoCompat.class.getClassLoader());
                present = Boolean.TRUE;
            } catch (Throwable t) {
                present = Boolean.FALSE;
            }
        }

        return present;
    }

    /**
     * Whether a player Yes Steve Model is not drawing would come out as a
     * model all the same if handed to the renderer behind this mod's.
     */
    public static boolean drawsModelsSwitchedOff() {
        if (!present()) {
            return false;
        }

        if (!said) {
            said = true;
            EpicYsm.LOGGER.info("Compatibility: YSM GEO Compat is installed (it comes inside Epic Fight: Touhou Little Maid). It draws"
                    + " the model chosen in Yes Steve Model on a player in battle whether Yes Steve Model is showing that model"
                    + " or not, so a model switched off came back in battle. A player Yes Steve Model is not drawing is drawn"
                    + " by this mod as a plain Epic Fight player, not handed to the renderer behind it");
        }

        return true;
    }

    /**
     * Whether a cancelled render of this player is that mod drawing the
     * player through Epic Fight itself - which it does for every player in
     * battle the vanilla renderer was about to draw - and not somebody
     * hiding the player.
     */
    public static boolean tookOver(AbstractClientPlayer player, Object renderer) {
        if (!present() || !(renderer instanceof PlayerRenderer)) {
            return false;
        }

        try {
            LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(player, LivingEntityPatch.class);
            return patch != null && patch.overrideRender();
        } catch (Throwable t) {
            return false;
        }
    }
}
