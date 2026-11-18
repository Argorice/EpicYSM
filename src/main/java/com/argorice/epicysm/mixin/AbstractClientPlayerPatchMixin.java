package com.argorice.epicysm.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import yesman.epicfight.api.utils.EntitySnapshot;
import yesman.epicfight.client.world.capabilites.entitypatch.player.AbstractClientPlayerPatch;

import com.argorice.epicysm.client.ysm.AfterimageSnapshots;

/**
 * Epic Fight's afterimage of a player is made out of a snapshot of the
 * body. For a player drawn with a Yes Steve Model model the snapshot is
 * this mod's, of the model on screen; for anyone else it is Epic Fight's.
 * A version of Epic Fight without such snapshots has no method to hook
 * into, and the hook is left out.
 */
@Mixin(value = AbstractClientPlayerPatch.class, remap = false)
public abstract class AbstractClientPlayerPatchMixin {
    @Inject(method = "captureEntitySnapshot()Lyesman/epicfight/api/utils/EntitySnapshot;", at = @At("HEAD"), cancellable = true,
            remap = false)
    private void epicysm$snapshotOfTheModel(CallbackInfoReturnable<EntitySnapshot<?>> callback) {
        Object snapshot = AfterimageSnapshots.capture((AbstractClientPlayerPatch<?>) (Object) this);

        if (snapshot != null) {
            callback.setReturnValue((EntitySnapshot<?>) snapshot);
        }
    }
}
