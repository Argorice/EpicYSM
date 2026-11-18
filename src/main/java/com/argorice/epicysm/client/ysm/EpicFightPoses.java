package com.argorice.epicysm.client.ysm;

import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * Epic Fight's pose for a body at an instant, asked for once a frame.
 *
 * The animator blends every layer afresh on each call, and in one frame the
 * same pose was asked for by the carry of the body, by the solver, by the
 * stand-in skeleton for each hand and by a weapon add-on - four or five
 * times, twice that under shaders. Nobody who asks writes into it, so one
 * copy per body per instant serves them all.
 */
public final class EpicFightPoses {
    private EpicFightPoses() {
    }

    /** Patch -> {the body's own tick, partial ticks, pose}; weak on the patch, which goes with its entity. */
    private static final Map<LivingEntityPatch<?>, Object[]> LAST = new WeakHashMap<>();

    @Nullable
    public static Pose of(LivingEntityPatch<?> patch, float partialTicks) {
        if (patch == null || patch.getAnimator() == null) {
            return null;
        }

        // The instant is the body's own tick, which is what its animator
        // steps on. The world's clock stands still while the world is
        // frozen and a player in it goes on moving.
        if (patch.getOriginal() == null) {
            return patch.getAnimator().getPose(partialTicks);
        }

        int now = patch.getOriginal().tickCount;
        Object[] last = LAST.get(patch);

        if (last != null && (Integer) last[0] == now && (Float) last[1] == partialTicks) {
            return (Pose) last[2];
        }

        Pose pose = patch.getAnimator().getPose(partialTicks);
        LAST.put(patch, new Object[] { now, partialTicks, pose });
        return pose;
    }
}
