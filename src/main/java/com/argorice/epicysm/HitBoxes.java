package com.argorice.epicysm;

import javax.annotation.Nullable;

import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.types.AttackAnimation;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * What the mixins into Epic Fight's hit boxes check before a box is placed
 * on the skeleton: the checks live here, where the mixins can share them.
 */
public final class HitBoxes {
    private static boolean saidMissing;

    private HitBoxes() {
    }

    /**
     * True when the joint an attack hangs its hit box on is not in the
     * skeleton the entity has right now, said once. A weapon that gives the
     * player a skeleton with joints of its own (Nightfall's thorn wheel)
     * hangs its attacks on those joints, and the skeleton goes back to the
     * biped the moment the weapon leaves the hand while the attack is
     * still playing; Epic Fight then asks the biped for the weapon's joint
     * and the server tick ends in an IllegalArgumentException.
     */
    public static boolean jointMissing(@Nullable LivingEntityPatch<?> patch, @Nullable Joint joint,
                                       @Nullable AttackAnimation animation) {
        if (patch == null || joint == null) {
            return false;
        }

        Armature armature;

        try {
            armature = patch.getArmature();
        } catch (Throwable t) {
            return false;
        }

        if (armature == null || armature.hasJoint(joint.getName())) {
            return false;
        }

        if (!saidMissing) {
            saidMissing = true;
            EpicYsm.LOGGER.warn("The attack {} hangs its hit box on the joint {}, which the skeleton {} of {} does not"
                    + " have; the box is left out while that is so. The animation belongs to a skeleton the entity"
                    + " no longer has - a weapon's, after the weapon left the hand - and the server tick would have"
                    + " ended in an IllegalArgumentException otherwise.", animation, joint.getName(), armature,
                    patch.getOriginal() == null ? "?" : patch.getOriginal().getName().getString());
        }

        return true;
    }
}
