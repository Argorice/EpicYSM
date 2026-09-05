package com.argorice.epicysm.client.convert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.argorice.epicysm.EpicYsm;
import com.argorice.epicysm.client.model.BedrockGeometry;
import com.argorice.epicysm.client.ysm.WeaponBones;

/**
 * One block in the log for every model converted: each bone as this mod
 * read it, what was made of it, and the skeleton that came out. A model
 * that ends up wrong can then be understood from the log alone - no need
 * to open the model.
 *
 * Bones are written in the model's own terms (pivots, rotations and boxes
 * in the units a model editor shows, x to the model's right); the joints
 * at the end are in blocks, as Epic Fight sees them.
 */
final class BoneReport {
    private BoneReport() {
    }

    /**
     * @param boundTo what the cubes of each bone were bound to, per bone
     *                (a joint name, or "extra:" and a bone name for a joint
     *                made for that bone)
     */
    static void log(String id, String displayName, BedrockGeometry geometry,
                    Map<String, BedrockGeometry.Bone> bonesByName, Map<String, Matrix4f> boneWorld,
                    float widthScale, float heightScale, @Nullable String mainRoot, JointMapper mapper,
                    Set<String> defaultHidden, Set<String> physicsCandidates,
                    List<PhysicsChains.Def> physicsDefs, List<PhysicsChains.Baked> physicsBaked,
                    List<AnimatedBones.Def> animatedDefs,
                    Map<String, Vector3f> jointPositions, List<String> guessedJoints, List<String> repairedJoints,
                    Map<String, Set<String>> boundTo) {
        try {
            Map<String, PhysicsChains.Def> physicsByBone = new LinkedHashMap<>();
            Map<Integer, PhysicsChains.Baked> bakedById = new LinkedHashMap<>();
            Map<String, AnimatedBones.Def> animatedByBone = new LinkedHashMap<>();

            for (PhysicsChains.Def def : physicsDefs) {
                physicsByBone.put(def.boneName(), def);
            }

            for (PhysicsChains.Baked baked : physicsBaked) {
                bakedById.put(baked.id(), baked);
            }

            for (AnimatedBones.Def def : animatedDefs) {
                animatedByBone.put(def.boneName(), def);
            }

            int cubes = 0;

            for (BedrockGeometry.Bone bone : geometry.bones()) {
                cubes += bone.cubes().size();
            }

            List<String> lines = new ArrayList<>();
            lines.add(String.format(Locale.ROOT, "Model %s (\"%s\"): %d bone(s), %d cube(s), drawn %.3f wide and %.3f tall,"
                    + " main root '%s'. Bones as authored: pivot and rotation as the model stores them, box = the bone's own"
                    + " cubes about its pivot at rest (x right, y up, z front, in model units); then what became of each",
                    id, displayName, geometry.bones().size(), cubes, widthScale, heightScale, mainRoot));

            for (BedrockGeometry.Bone bone : orderedByTree(geometry, bonesByName)) {
                lines.add(describe(bone, bonesByName, boneWorld, mainRoot, mapper, defaultHidden, physicsCandidates,
                        physicsByBone, bakedById, animatedByBone, boundTo));
            }

            lines.add(joints(jointPositions, mapper, guessedJoints, repairedJoints));
            EpicYsm.LOGGER.info(String.join("\n  ", lines));
        } catch (Throwable t) {
            EpicYsm.LOGGER.debug("Could not describe model {}", id, t);
        }
    }

    private static String describe(BedrockGeometry.Bone bone, Map<String, BedrockGeometry.Bone> bonesByName,
                                   Map<String, Matrix4f> boneWorld, @Nullable String mainRoot, JointMapper mapper,
                                   Set<String> defaultHidden, Set<String> physicsCandidates,
                                   Map<String, PhysicsChains.Def> physicsByBone, Map<Integer, PhysicsChains.Baked> bakedById,
                                   Map<String, AnimatedBones.Def> animatedByBone, Map<String, Set<String>> boundTo) {
        StringBuilder out = new StringBuilder();
        out.append(depthOf(bone, bonesByName) == 0 ? "" : "  ".repeat(depthOf(bone, bonesByName)));
        out.append(bone.name());

        if (bone.parent() != null) {
            out.append(" < ").append(bone.parent());
        }

        float[] pivot = bone.pivot();
        float[] rotation = bone.rotation();
        out.append(String.format(Locale.ROOT, " | pivot %.2f %.2f %.2f", pivot[0], pivot[1], pivot[2]));

        if (rotation[0] != 0.0F || rotation[1] != 0.0F || rotation[2] != 0.0F) {
            out.append(String.format(Locale.ROOT, " rot %.1f %.1f %.1f", rotation[0], rotation[1], rotation[2]));
        }

        if (bone.mirror()) {
            out.append(" mirrored");
        }

        if (bone.cubes().isEmpty()) {
            out.append(" | no cubes");
        } else {
            float[] box = box(bone, boneWorld);
            out.append(String.format(Locale.ROOT, " | %d cube(s), box x %.1f..%.1f y %.1f..%.1f z %.1f..%.1f",
                    bone.cubes().size(), box[0], box[3], box[1], box[4], box[2], box[5]));
        }

        // What became of it.
        List<String> fate = new ArrayList<>();
        String joint = mapper.jointFor(bone);

        if (mainRoot != null && !mainRoot.equals(rootAncestorName(bone, bonesByName))) {
            fate.add("prop outside the main root, not drawn in battle");
        } else if (joint == null) {
            fate.add(defaultHidden.contains(bone.name()) ? "hidden (the model keeps it hidden, or it is a weapon of its own)"
                    : hiddenByAncestor(bone, bonesByName, defaultHidden) ? "hidden under a hidden bone"
                    : "hidden by epicysm.json");
        } else if (!bone.cubes().isEmpty()) {
            Set<String> bound = boundTo.get(bone.name());

            if (bound == null || bound.isEmpty()) {
                fate.add("-> " + joint);
            } else {
                List<String> names = new ArrayList<>();

                for (String target : bound) {
                    names.add(target.startsWith("extra:") ? "the joint made for " + target.substring(6) : target);
                }

                fate.add("-> " + String.join(", ", names));
            }

            fate.add(mapper.hasKnownName(bone) ? "by name" : "by the nearest joint (no known name above it)");
        } else {
            fate.add("(cubes below it -> " + joint + ")");
        }

        PhysicsChains.Def physics = physicsByBone.get(bone.name());

        if (physics != null) {
            PhysicsChains.Baked baked = bakedById.get(physics.id());
            Vector3f rest = physics.restOffset();
            fate.add(String.format(Locale.ROOT, "physics: hangs from %s, rest offset %.2f %.2f %.2f blocks%s%s",
                    physics.parentName(), rest.x(), rest.y(), rest.z(),
                    baked != null && baked.chainRoot() ? ", chain root" : "",
                    baked != null && baked.aroundLegs() ? ", kept off the legs" : ""));
        } else if (physicsCandidates.contains(bone.name())) {
            fate.add("physics candidate, kept rigid (sits about its pivot, or nothing to swing)");
        }

        AnimatedBones.Def animated = animatedByBone.get(bone.name());

        if (animated != null) {
            fate.add("plays the model's own animation");
        }

        if (WeaponBones.isHandLocator(bone.name())) {
            fate.add("hand locator");
        }

        if (WeaponBones.isWeapon(bone.name())) {
            fate.add("weapon of the model's own");
        }

        return out.append(" | ").append(String.join("; ", fate)).toString();
    }

    /** The skeleton built for Epic Fight, in blocks, and where each joint was taken from. */
    private static String joints(Map<String, Vector3f> jointPositions, JointMapper mapper,
                                 List<String> guessedJoints, List<String> repairedJoints) {
        StringBuilder out = new StringBuilder("Joints for Epic Fight (blocks; x to the model's right, y up, z front):");

        for (String joint : JointMapper.JOINT_NAMES) {
            Vector3f at = jointPositions.get(joint);

            if (at == null) {
                continue;
            }

            BedrockGeometry.Bone source = mapper.pivotSource(joint);
            out.append(String.format(Locale.ROOT, "\n    %-10s %7.3f %7.3f %7.3f", joint, at.x(), at.y(), at.z()));

            if (guessedJoints.contains(joint) || source == null) {
                out.append("  (no bone for it; taken from the biped)");
            } else {
                out.append("  <- ").append(source.name());
            }

            if (repairedJoints.contains(joint)) {
                out.append("  (moved to a body's proportions)");
            }
        }

        return out.toString();
    }

    /**
     * The bone's own cubes about its pivot at rest: min x, y, z then max
     * x, y, z, in model units with x to the model's right.
     */
    private static float[] box(BedrockGeometry.Bone bone, Map<String, Matrix4f> boneWorld) {
        Matrix4f world = boneWorld.get(bone.name());
        float[] box = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };

        if (world == null) {
            return new float[6];
        }

        Vector3f pivot = world.transformPosition(new Vector3f(-bone.pivot()[0] / 16.0F, bone.pivot()[1] / 16.0F, bone.pivot()[2] / 16.0F));

        for (BedrockGeometry.Cube cube : bone.cubes()) {
            float cpx = -cube.pivot()[0] / 16.0F;
            float cpy = cube.pivot()[1] / 16.0F;
            float cpz = cube.pivot()[2] / 16.0F;
            Matrix4f matrix = new Matrix4f(world)
                    .translate(cpx, cpy, cpz)
                    .rotateZ((float) Math.toRadians(cube.rotation()[2]))
                    .rotateY((float) Math.toRadians(-cube.rotation()[1]))
                    .rotateX((float) Math.toRadians(-cube.rotation()[0]))
                    .translate(-cpx, -cpy, -cpz);
            float inflate = (cube.hasInflate() ? cube.inflate() : bone.inflate()) / 16.0F;
            float x0 = -(cube.origin()[0] + cube.size()[0]) / 16.0F - inflate;
            float x1 = -cube.origin()[0] / 16.0F + inflate;
            float y0 = cube.origin()[1] / 16.0F - inflate;
            float y1 = (cube.origin()[1] + cube.size()[1]) / 16.0F + inflate;
            float z0 = cube.origin()[2] / 16.0F - inflate;
            float z1 = (cube.origin()[2] + cube.size()[2]) / 16.0F + inflate;

            for (int corner = 0; corner < 8; corner++) {
                Vector3f point = matrix.transformPosition(new Vector3f(
                        (corner & 1) == 0 ? x0 : x1, (corner & 2) == 0 ? y0 : y1, (corner & 4) == 0 ? z0 : z1));
                // Back to the model's own hand: x to its right, in units.
                float x = -(point.x() - pivot.x()) * 16.0F;
                float y = (point.y() - pivot.y()) * 16.0F;
                float z = (point.z() - pivot.z()) * 16.0F;
                box[0] = Math.min(box[0], x);
                box[1] = Math.min(box[1], y);
                box[2] = Math.min(box[2], z);
                box[3] = Math.max(box[3], x);
                box[4] = Math.max(box[4], y);
                box[5] = Math.max(box[5], z);
            }
        }

        return box;
    }

    private static boolean hiddenByAncestor(BedrockGeometry.Bone bone, Map<String, BedrockGeometry.Bone> bonesByName,
                                            Set<String> defaultHidden) {
        for (BedrockGeometry.Bone current = bone.parent() != null ? bonesByName.get(bone.parent()) : null; current != null;
                current = current.parent() != null ? bonesByName.get(current.parent()) : null) {
            if (defaultHidden.contains(current.name())) {
                return true;
            }
        }

        return false;
    }

    private static int depthOf(BedrockGeometry.Bone bone, Map<String, BedrockGeometry.Bone> bonesByName) {
        int depth = 0;

        for (BedrockGeometry.Bone current = bone; current.parent() != null && depth < 64; depth++) {
            current = bonesByName.get(current.parent());

            if (current == null) {
                break;
            }
        }

        return depth;
    }

    private static String rootAncestorName(BedrockGeometry.Bone bone, Map<String, BedrockGeometry.Bone> bonesByName) {
        BedrockGeometry.Bone current = bone;

        for (int step = 0; current.parent() != null && step < 64; step++) {
            BedrockGeometry.Bone parent = bonesByName.get(current.parent());

            if (parent == null) {
                break;
            }

            current = parent;
        }

        return current.name();
    }

    /** Parents before children, siblings in the order the model lists them. */
    private static List<BedrockGeometry.Bone> orderedByTree(BedrockGeometry geometry, Map<String, BedrockGeometry.Bone> bonesByName) {
        Map<String, List<BedrockGeometry.Bone>> children = new LinkedHashMap<>();
        List<BedrockGeometry.Bone> roots = new ArrayList<>();

        for (BedrockGeometry.Bone bone : geometry.bones()) {
            if (bone.parent() != null && bonesByName.containsKey(bone.parent())) {
                children.computeIfAbsent(bone.parent(), key -> new ArrayList<>()).add(bone);
            } else {
                roots.add(bone);
            }
        }

        List<BedrockGeometry.Bone> ordered = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();

        for (BedrockGeometry.Bone root : roots) {
            walk(root, children, ordered, seen, 0);
        }

        // Anything left out by a loop in the parents is still listed.
        for (BedrockGeometry.Bone bone : geometry.bones()) {
            if (seen.add(bone.name())) {
                ordered.add(bone);
            }
        }

        return ordered;
    }

    private static void walk(BedrockGeometry.Bone bone, Map<String, List<BedrockGeometry.Bone>> children,
                             List<BedrockGeometry.Bone> out, Set<String> seen, int depth) {
        if (depth > 64 || !seen.add(bone.name())) {
            return;
        }

        out.add(bone);

        for (BedrockGeometry.Bone child : children.getOrDefault(bone.name(), List.of())) {
            walk(child, children, out, seen, depth + 1);
        }
    }
}
