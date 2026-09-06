package com.argorice.epicysm.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.phys.Vec3;
import yesman.epicfight.api.collider.OBBCollider;

/** The corners and the face normals of a hit box, as placed for this tick. */
@Mixin(value = OBBCollider.class, remap = false)
public interface OBBColliderAccess {
    @Accessor("rotatedVertices")
    Vec3[] epicysm$rotatedVertices();

    @Accessor("rotatedNormals")
    Vec3[] epicysm$rotatedNormals();
}
