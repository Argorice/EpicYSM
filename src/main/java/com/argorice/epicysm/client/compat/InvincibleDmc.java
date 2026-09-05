package com.argorice.epicysm.client.compat;

import java.lang.reflect.Method;
import java.util.Optional;

import javax.annotation.Nullable;

import net.minecraft.client.player.AbstractClientPlayer;
import yesman.epicfight.api.animation.AnimationPlayer;
import yesman.epicfight.api.animation.property.AnimationProperty;
import yesman.epicfight.api.animation.types.DynamicAnimation;
import yesman.epicfight.api.utils.TimePairList;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import com.argorice.epicysm.EpicYsm;

/**
 * Invincible: DevilMineCraft (invincible_dmc). Two things it does to a
 * player's look:
 *
 * Sin Devil Trigger draws a demon over the player with a renderer of its
 * own, on Epic Fight's ordinary skeleton - so the skeleton gives nothing
 * away and its renderer has to be asked directly. While it says the demon
 * is on screen (the transformation in and out included), that renderer
 * draws the player.
 *
 * Some Yamato attacks make the player vanish for a moment - the mod marks
 * those moments on the animation and cancels the player's render event.
 * That event is also where this mod steps in, ahead of it, so the moments
 * are read off the animation here as well and nothing is drawn in them.
 *
 * Everything is reached by reflection: the mod is optional and its classes
 * may not be there.
 */
final class InvincibleDmc implements LookOwners.Detector {
    private static final String RENDERER = "com.dmc.invincible_dmc.client.renderer.patched.entity.PSdtPlayerRenderer";
    private static final String YAMATO = "com.dmc.invincible_dmc.api.animation.types.yamato.YamatoAttackAnimation";

    private final Method shouldRenderSdtMesh;
    @Nullable
    private final AnimationProperty<?> invisibleTime;

    private InvincibleDmc(Method shouldRenderSdtMesh, @Nullable AnimationProperty<?> invisibleTime) {
        this.shouldRenderSdtMesh = shouldRenderSdtMesh;
        this.invisibleTime = invisibleTime;
    }

    /** Registers the detector when the mod is installed; does nothing otherwise. */
    static void registerIfPresent() {
        Method shouldRender;

        try {
            shouldRender = Class.forName(RENDERER).getMethod("shouldRenderSdtMesh", AbstractClientPlayer.class);
        } catch (Throwable t) {
            return;
        }

        AnimationProperty<?> invisible = null;

        try {
            Object value = Class.forName(YAMATO).getField("INVISIBLE_TIME").get(null);

            if (value instanceof AnimationProperty<?> property) {
                invisible = property;
            }
        } catch (Throwable t) {
            EpicYsm.LOGGER.debug("Invincible: DevilMineCraft is here but its invisible-time property is not", t);
        }

        LookOwners.register(new InvincibleDmc(shouldRender, invisible));
    }

    @Override
    public String name() {
        return "Invincible: DevilMineCraft";
    }

    @Override
    @Nullable
    public String owns(AbstractClientPlayer player, LivingEntityPatch<?> patch) {
        try {
            return Boolean.TRUE.equals(this.shouldRenderSdtMesh.invoke(null, player)) ? "Sin Devil Trigger" : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    @Nullable
    public String hides(AbstractClientPlayer player, LivingEntityPatch<?> patch) {
        if (this.invisibleTime == null) {
            return null;
        }

        try {
            AnimationPlayer animationPlayer = patch.getClientAnimator().baseLayer.animationPlayer;
            float elapsed = animationPlayer.getElapsedTime();
            DynamicAnimation animation = animationPlayer.getAnimation().get();
            DynamicAnimation real = animationPlayer.getRealAnimation().get();

            if (inInvisibleTime(animation, elapsed) || inInvisibleTime(real, elapsed)) {
                return "an attack in which the character vanishes";
            }
        } catch (Throwable t) {
            // No animation to read; the player is not hidden.
        }

        return null;
    }

    private boolean inInvisibleTime(@Nullable DynamicAnimation animation, float elapsed) {
        if (animation == null) {
            return false;
        }

        Optional<?> pairs = animation.getProperty(this.invisibleTime);
        return pairs.isPresent() && pairs.get() instanceof TimePairList list && list.isTimeInPairs(elapsed);
    }
}
