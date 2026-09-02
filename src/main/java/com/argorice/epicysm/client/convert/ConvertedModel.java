package com.argorice.epicysm.client.convert;

import net.minecraft.resources.ResourceLocation;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.model.armature.HumanoidArmature;

/**
 * The result of converting one YSM model: an Epic Fight mesh skinned to the
 * biped joint ids, an armature with biped joint names but the model's own
 * pivot positions, the registered texture, and the physics joints appended
 * to the armature for secondary motion (hair, tails, skirts).
 */
public record ConvertedModel(String id,
                             String displayName,
                             HumanoidMesh mesh,
                             HumanoidArmature armature,
                             ResourceLocation texture,
                             boolean translucent,
                             java.util.List<PhysicsChains.Baked> physicsJoints) {
}
