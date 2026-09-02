package com.argorice.epicysm.client.convert;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import com.argorice.epicysm.client.model.BedrockGeometry;

/** Decides which Epic Fight biped joint each YSM bone belongs to. */
public final class JointMapper {
    /** Epic Fight biped joint ids, matching assets/epicfight/animmodels/entity/biped.json. */
    public static final String[] JOINT_NAMES = {
            "Root", "Thigh_R", "Leg_R", "Knee_R", "Thigh_L", "Leg_L", "Knee_L",
            "Torso", "Chest", "Head", "Shoulder_R", "Arm_R", "Hand_R", "Tool_R",
            "Elbow_R", "Shoulder_L", "Arm_L", "Hand_L", "Tool_L", "Elbow_L"
    };

    /** Default cube ownership: YSM bone name (lower case) to EF joint name. */
    private static final Map<String, String> DEFAULT_BONE_TO_JOINT = new HashMap<>();

    /** Default pivot sources: EF joint name to YSM bone name whose pivot is used. */
    private static final Map<String, String[]> DEFAULT_PIVOT_SOURCES = new HashMap<>();

    /** Actual skeleton bones (lower case); never treated as loose decoration. */
    private static final Set<String> CORE_BONE_NAMES = Set.of(
            "root", "mroot", "head", "allhead", "mhead", "upperbody", "mupperbody",
            "upbody", "allbody", "mallbody", "downbody", "body", "waist", "breast",
            "leftarm", "leftforearm", "lefthand", "rightarm", "rightforearm", "righthand", "arm",
            "leftleg", "leftlowerleg", "leftfoot", "rightleg", "rightlowerleg", "rightfoot", "leg");

    public static boolean isCoreBoneName(String lowerName) {
        return CORE_BONE_NAMES.contains(lowerName);
    }

    static {
        map("head", "Head");
        map("allhead", "Head");
        map("hair", "Head");
        map("face", "Head");

        map("upperbody", "Chest");
        map("mupperbody", "Chest");
        map("breast", "Chest");
        map("collar", "Chest");

        map("upbody", "Torso");
        map("allbody", "Torso");
        map("mallbody", "Torso");
        map("downbody", "Torso");
        map("body", "Torso");
        map("skirt", "Torso");
        map("waist", "Torso");

        map("leftarm", "Arm_L");
        map("leftforearm", "Hand_L");
        map("lefthand", "Hand_L");
        map("rightarm", "Arm_R");
        map("rightforearm", "Hand_R");
        map("righthand", "Hand_R");
        map("arm", "Chest");

        map("leftleg", "Thigh_L");
        map("leftlowerleg", "Leg_L");
        map("leftfoot", "Leg_L");
        map("rightleg", "Thigh_R");
        map("rightlowerleg", "Leg_R");
        map("rightfoot", "Leg_R");
        map("leg", "Torso");

        pivots("Root", "AllBody", "MAllBody", "UpBody");
        pivots("Torso", "UpBody", "AllBody", "DownBody");
        pivots("Chest", "UpperBody", "UpBody");
        pivots("Head", "Head", "AllHead");

        pivots("Shoulder_R", "RightArm");
        pivots("Arm_R", "RightArm");
        pivots("Hand_R", "RightForeArm", "RightArm");
        pivots("Elbow_R", "RightForeArm", "RightArm");
        pivots("Tool_R", "RightHandLocator", "RightHand", "RightForeArm");

        pivots("Shoulder_L", "LeftArm");
        pivots("Arm_L", "LeftArm");
        pivots("Hand_L", "LeftForeArm", "LeftArm");
        pivots("Elbow_L", "LeftForeArm", "LeftArm");
        pivots("Tool_L", "LeftHandLocator", "LeftHand", "LeftForeArm");

        pivots("Thigh_R", "RightLeg");
        pivots("Leg_R", "RightLowerLeg", "RightLeg");
        pivots("Knee_R", "RightLowerLeg", "RightLeg");

        pivots("Thigh_L", "LeftLeg");
        pivots("Leg_L", "LeftLowerLeg", "LeftLeg");
        pivots("Knee_L", "LeftLowerLeg", "LeftLeg");
    }

    private static void map(String boneName, String jointName) {
        DEFAULT_BONE_TO_JOINT.put(boneName, jointName);
    }

    private static void pivots(String jointName, String... boneNames) {
        DEFAULT_PIVOT_SOURCES.put(jointName, boneNames);
    }

    private final Map<String, String> boneToJoint = new HashMap<>();
    private final Map<String, String> pivotOverrides = new HashMap<>();
    private final Set<String> hiddenBones = new HashSet<>();
    private final Map<String, BedrockGeometry.Bone> bonesByName = new HashMap<>();

    public JointMapper(BedrockGeometry geometry, @Nullable JsonObject overrides) {
        for (BedrockGeometry.Bone bone : geometry.bones()) {
            this.bonesByName.put(bone.name(), bone);
        }

        if (overrides != null) {
            if (overrides.has("bones")) {
                for (Map.Entry<String, com.google.gson.JsonElement> entry : overrides.getAsJsonObject("bones").entrySet()) {
                    String target = entry.getValue().getAsString();

                    if ("hide".equalsIgnoreCase(target)) {
                        this.hiddenBones.add(entry.getKey());
                    } else {
                        this.boneToJoint.put(entry.getKey().toLowerCase(Locale.ROOT), target);
                    }
                }
            }

            if (overrides.has("pivots")) {
                for (Map.Entry<String, com.google.gson.JsonElement> entry : overrides.getAsJsonObject("pivots").entrySet()) {
                    this.pivotOverrides.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
    }

    /**
     * Hides bones the model itself keeps invisible by default (detected
     * from its always-playing animations). An explicit mapping for a bone
     * in epicysm.json wins over auto-hiding, so a model can force one of
     * these bones to show.
     */
    public void autoHide(Set<String> boneNames) {
        for (String name : boneNames) {
            if (!this.boneToJoint.containsKey(name.toLowerCase(Locale.ROOT))) {
                this.hiddenBones.add(name);
            }
        }
    }

    /**
     * Resolves the EF joint owning the cubes of the given bone, walking up
     * the parent chain until a mapped bone is found. Returns null for bones
     * that are explicitly hidden (including via a hidden ancestor).
     */
    @Nullable
    public String jointFor(BedrockGeometry.Bone bone) {
        String explicitSelf = this.boneToJoint.get(bone.name().toLowerCase(Locale.ROOT));

        if (explicitSelf == null) {
            for (BedrockGeometry.Bone current = bone; current != null;
                    current = current.parent() != null ? this.bonesByName.get(current.parent()) : null) {
                if (this.hiddenBones.contains(current.name())) {
                    return null;
                }
            }
        }

        BedrockGeometry.Bone current = bone;

        while (current != null) {
            String explicit = this.boneToJoint.get(current.name().toLowerCase(Locale.ROOT));

            if (explicit == null) {
                explicit = DEFAULT_BONE_TO_JOINT.get(current.name().toLowerCase(Locale.ROOT));
            }

            if (explicit != null) {
                return explicit;
            }

            current = current.parent() != null ? this.bonesByName.get(current.parent()) : null;
        }

        return "Torso";
    }

    /**
     * Whether anything in this bone's ancestry carries a name this mod
     * knows. When nothing does, jointFor's answer is only a default, and the
     * bone is a prop that should be placed by where it actually sits rather
     * than by a name that was never matched.
     */
    public boolean hasKnownName(BedrockGeometry.Bone bone) {
        for (BedrockGeometry.Bone current = bone; current != null;
                current = current.parent() != null ? this.bonesByName.get(current.parent()) : null) {
            String name = current.name().toLowerCase(Locale.ROOT);

            if (this.boneToJoint.containsKey(name) || DEFAULT_BONE_TO_JOINT.containsKey(name)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns the YSM bone whose (world) pivot position should become the
     * bind position of the given EF joint, or null when the model has none
     * of the candidate bones.
     */
    @Nullable
    public BedrockGeometry.Bone pivotSource(String jointName) {
        String override = this.pivotOverrides.get(jointName);

        if (override != null) {
            BedrockGeometry.Bone bone = this.bonesByName.get(override);

            if (bone != null) {
                return bone;
            }
        }

        String[] candidates = DEFAULT_PIVOT_SOURCES.get(jointName);

        if (candidates != null) {
            for (String candidate : candidates) {
                BedrockGeometry.Bone bone = findBoneIgnoreCase(candidate);

                if (bone != null) {
                    return bone;
                }
            }
        }

        return null;
    }

    @Nullable
    private BedrockGeometry.Bone findBoneIgnoreCase(String name) {
        BedrockGeometry.Bone bone = this.bonesByName.get(name);

        if (bone != null) {
            return bone;
        }

        for (Map.Entry<String, BedrockGeometry.Bone> entry : this.bonesByName.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }

        return null;
    }

    public static int jointId(String jointName) {
        for (int i = 0; i < JOINT_NAMES.length; i++) {
            if (JOINT_NAMES[i].equals(jointName)) {
                return i;
            }
        }

        return 7; // Torso
    }

    /** Mesh part bucket for the given joint, matching HumanoidMesh part names. */
    public static String partNameFor(String jointName) {
        return switch (jointName) {
            case "Head" -> "head";
            case "Arm_L", "Hand_L", "Elbow_L", "Shoulder_L", "Tool_L" -> "leftArm";
            case "Arm_R", "Hand_R", "Elbow_R", "Shoulder_R", "Tool_R" -> "rightArm";
            case "Thigh_L", "Leg_L", "Knee_L" -> "leftLeg";
            case "Thigh_R", "Leg_R", "Knee_R" -> "rightLeg";
            default -> "torso";
        };
    }
}
