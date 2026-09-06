package com.argorice.epicysm.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.phys.Vec3;
import yesman.epicfight.api.collider.OBBCollider;

import com.argorice.epicysm.EpicYsm;

/**
 * A hit box whose corners are not numbers hits nothing.
 *
 * Epic Fight tests an attack's hit box against a target by projecting the
 * corners of both boxes onto the face normals of both and keeping, per
 * axis, the corner that reaches furthest. A corner that is not a number
 * reaches nowhere - every comparison with NaN is false - so no corner is
 * kept, and projecting the corner that was never kept ends the server
 * tick in a NullPointerException, and the game with it. Such a box comes
 * out of a transform that is not a number; Invincible: DevilMineCraft's
 * Yamato, which resolves its own hit boxes, ended a session that way. Here
 * a box with a corner or a normal that is not a finite number is answered
 * with no collision for the tick, and the game goes on. On a dedicated
 * server, where this mod is not, nothing changes.
 */
@Mixin(value = OBBCollider.class, remap = false)
public abstract class OBBColliderMixin {
    private static boolean epicysm$said;

    @Inject(method = "isCollide(Lyesman/epicfight/api/collider/OBBCollider;)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void epicysm$refuseNotNumbers(OBBCollider other, CallbackInfoReturnable<Boolean> cir) {
        boolean mine = epicysm$notNumbers((Object) this);
        boolean theirs = epicysm$notNumbers(other);

        if (mine || theirs) {
            if (!epicysm$said) {
                epicysm$said = true;
                EpicYsm.LOGGER.warn("Epic Fight asked whether a hit box collides while a corner of it is not a finite"
                        + " number ({}); no hit is counted for it. The mod that placed the box gave it a transform"
                        + " that is not a number - the server tick would have ended in a NullPointerException"
                        + " otherwise.", (mine ? this : other).getClass().getName());
            }

            cir.setReturnValue(false);
        }
    }

    private static boolean epicysm$notNumbers(Object collider) {
        if (!(collider instanceof OBBColliderAccess access)) {
            return false;
        }

        return epicysm$notNumbers(access.epicysm$rotatedVertices()) || epicysm$notNumbers(access.epicysm$rotatedNormals());
    }

    private static boolean epicysm$notNumbers(Vec3[] points) {
        if (points == null || points.length == 0) {
            return true;
        }

        for (Vec3 point : points) {
            if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
                return true;
            }
        }

        return false;
    }
}
