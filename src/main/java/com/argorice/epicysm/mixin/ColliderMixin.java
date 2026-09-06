package com.argorice.epicysm.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.types.AttackAnimation;
import yesman.epicfight.api.collider.Collider;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import com.argorice.epicysm.HitBoxes;

/**
 * A hit box hung on a joint the skeleton does not have hits nothing. See
 * {@link HitBoxes#jointMissing}; the box of several is in
 * {@link MultiColliderMixin}, which has an update of its own.
 */
@Mixin(value = Collider.class, remap = false)
public abstract class ColliderMixin {
    @Inject(method = "updateAndSelectCollideEntity(Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;"
            + "Lyesman/epicfight/api/animation/types/AttackAnimation;FFLyesman/epicfight/api/animation/Joint;F)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void epicysm$skipMissingJoint(LivingEntityPatch<?> entitypatch, AttackAnimation animation, float prevElapsedTime,
                                          float elapsedTime, Joint joint, float attackSpeed, CallbackInfoReturnable<List<Entity>> cir) {
        if (HitBoxes.jointMissing(entitypatch, joint, animation)) {
            cir.setReturnValue(new ArrayList<>());
        }
    }
}
