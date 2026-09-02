package com.argorice.epicysm.client.convert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.argorice.epicysm.client.model.BedrockGeometry;

/**
 * Picks the bones of a model that should swing freely - hair, tails,
 * skirts, capes, ribbons - and describes them as physics joints appended to
 * the converted armature.
 */
public final class PhysicsChains {
    /** Extra joints start after the 20 biped joints. */
    public static final int FIRST_PHYSICS_JOINT_ID = JointMapper.JOINT_NAMES.length;
    private static final int MAX_JOINTS = 40;

    private static final String[] NAME_HINTS = {
            "hair", "tail", "ponytail", "twin", "qun", "skirt", "dress",
            "cape", "cloak", "mantle", "scarf", "ribbon", "sash", "tassel", "braid"
    };

    /**
     * One physics joint: appended to the armature under parentName, owning
     * the cubes of its bone (and non-physics descendants). worldPivot and
     * restOffset are in scaled model space at bind pose; restOffset points
     * from the pivot toward the tip the simulation swings.
     */
    public record Def(String boneName, int id, String parentName, int parentId,
                      Vector3f worldPivot, Vector3f restOffset) {
    }

    /**
     * A physics joint as the runtime needs it: ids into the pose matrix
     * array and the rest offset expressed in the joint's own bind frame.
     */
    public record Baked(int id, int parentId, Vector3f restLocal) {
    }

    private PhysicsChains() {
    }

    public static List<Def> collect(BedrockGeometry geometry, JointMapper mapper,
                                    List<JsonObject> animationFiles, JsonObject overrides,
                                    Map<String, BedrockGeometry.Bone> bonesByName,
                                    Map<String, Matrix4f> boneWorld,
                                    String mainRoot,
                                    float widthScale, float heightScale) {
        Set<String> animated = motionAnimatedBones(animationFiles);
        Set<String> added = new HashSet<>();
        Set<String> removed = new HashSet<>();

        if (overrides != null && overrides.has("physics") && overrides.get("physics").isJsonObject()) {
            JsonObject physics = overrides.getAsJsonObject("physics");
            readNames(physics, "add", added);
            readNames(physics, "remove", removed);
        }

        Map<String, List<BedrockGeometry.Bone>> children = new HashMap<>();

        for (BedrockGeometry.Bone bone : geometry.bones()) {
            if (bone.parent() != null) {
                children.computeIfAbsent(bone.parent(), key -> new ArrayList<>()).add(bone);
            }
        }

        // Subtree cube counts: a "physics" bone that would swing a third of
        // the whole model is a mis-detected wrapper, not a lock of hair.
        Map<String, Integer> subtreeCubes = new HashMap<>();
        int totalCubes = 0;

        for (BedrockGeometry.Bone bone : geometry.bones()) {
            totalCubes += bone.cubes().size();
        }

        int subtreeLimit = Math.max(30, (int) (totalCubes * 0.35F));

        Set<String> candidates = new HashSet<>();

        for (BedrockGeometry.Bone bone : geometry.bones()) {
            if (mainRoot != null && !mainRoot.equals(rootAncestor(bone, bonesByName))) {
                continue; // stand-alone prop trees are not part of the body
            }

            if (countSubtreeCubes(bone, children, subtreeCubes) > subtreeLimit && !added.contains(bone.name())) {
                continue;
            }

            if (isCandidate(bone, mapper, animated, added, removed, children)) {
                candidates.add(bone.name());
            }
        }

        // Emit chains parent-first so the simulation can update in order.
        List<Def> defs = new ArrayList<>();
        Map<String, Integer> idByBone = new HashMap<>();

        for (BedrockGeometry.Bone bone : orderedByDepth(geometry, bonesByName)) {
            if (!candidates.contains(bone.name()) || defs.size() >= MAX_JOINTS) {
                continue;
            }

            String parentName;
            int parentId;
            BedrockGeometry.Bone parentBone = bone.parent() != null ? bonesByName.get(bone.parent()) : null;

            if (parentBone != null && idByBone.containsKey(parentBone.name())) {
                parentId = idByBone.get(parentBone.name());
                parentName = parentBone.name();
            } else {
                String jointName = mapper.jointFor(bone);

                if (jointName == null) {
                    continue; // hidden
                }

                parentName = jointName;
                parentId = JointMapper.jointId(jointName);
            }

            Vector3f pivot = worldPivot(bone, boneWorld).mul(widthScale, heightScale, widthScale);
            Vector3f rest = restOffset(bone, candidates, children, boneWorld, widthScale, heightScale, pivot);

            if (rest.length() < 0.02F) {
                continue; // nothing to swing
            }

            int id = FIRST_PHYSICS_JOINT_ID + defs.size();
            idByBone.put(bone.name(), id);
            defs.add(new Def(bone.name(), id, parentName, parentId, pivot, rest));
        }

        return defs;
    }

    private static boolean isCandidate(BedrockGeometry.Bone bone, JointMapper mapper, Set<String> animated,
                                       Set<String> added, Set<String> removed,
                                       Map<String, List<BedrockGeometry.Bone>> children) {
        String name = bone.name();
        String lower = name.toLowerCase(Locale.ROOT);

        if (removed.contains(name)) {
            return false;
        }

        if (added.contains(name)) {
            // Explicit opt-in from epicysm.json bypasses every filter except
            // visibility, which isCandidate's caller has already checked.
            return mapper.jointFor(bone) != null;
        }

        if (lower.contains("locator") || JointMapper.isCoreBoneName(lower)) {
            return false;
        }

        // Limb-like bones (a second pair of legs, paws, extra hands) are
        // animated in walk cycles but must never turn to jelly.
        if (lower.contains("leg") || lower.contains("foot") || lower.contains("feet")
                || lower.contains("hand") || (lower.contains("arm") && !lower.contains("armor"))) {
            return false;
        }

        String jointName = mapper.jointFor(bone);

        if (jointName == null) {
            return false; // hidden
        }

        // Only decorations riding the body core swing; limbs stay rigid.
        String part = JointMapper.partNameFor(jointName);

        if (!"torso".equals(part) && !"head".equals(part)) {
            return false;
        }

        if (!hasGeometry(bone, children)) {
            return false;
        }

        if (animated.contains(name)) {
            return true;
        }

        for (String hint : NAME_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }

        return false;
    }

    private static int countSubtreeCubes(BedrockGeometry.Bone bone, Map<String, List<BedrockGeometry.Bone>> children,
                                         Map<String, Integer> cache) {
        Integer cached = cache.get(bone.name());

        if (cached != null) {
            return cached;
        }

        int total = bone.cubes().size();

        for (BedrockGeometry.Bone child : children.getOrDefault(bone.name(), List.of())) {
            total += countSubtreeCubes(child, children, cache);
        }

        cache.put(bone.name(), total);
        return total;
    }

    private static boolean hasGeometry(BedrockGeometry.Bone bone, Map<String, List<BedrockGeometry.Bone>> children) {
        if (!bone.cubes().isEmpty()) {
            return true;
        }

        for (BedrockGeometry.Bone child : children.getOrDefault(bone.name(), List.of())) {
            if (hasGeometry(child, children)) {
                return true;
            }
        }

        return false;
    }

    /** Bones whose rotation is keyframed in idle / walk / run animations. */
    private static Set<String> motionAnimatedBones(List<JsonObject> animationFiles) {
        Set<String> result = new HashSet<>();

        for (JsonObject file : animationFiles) {
            if (!file.has("animations") || !file.get("animations").isJsonObject()) {
                continue;
            }

            for (Map.Entry<String, JsonElement> animation : file.getAsJsonObject("animations").entrySet()) {
                String name = animation.getKey().toLowerCase(Locale.ROOT);

                if (!(name.contains("idle") || name.contains("walk") || name.contains("run")) || !animation.getValue().isJsonObject()) {
                    continue;
                }

                JsonObject definition = animation.getValue().getAsJsonObject();

                if (!definition.has("bones") || !definition.get("bones").isJsonObject()) {
                    continue;
                }

                for (Map.Entry<String, JsonElement> bone : definition.getAsJsonObject("bones").entrySet()) {
                    if (!bone.getValue().isJsonObject()) {
                        continue;
                    }

                    JsonElement rotation = bone.getValue().getAsJsonObject().get("rotation");

                    // Keyframed (several time keys) means the author animates
                    // this bone's swing; a single constant is just a pose.
                    if (rotation != null && rotation.isJsonObject() && rotation.getAsJsonObject().size() >= 2) {
                        result.add(bone.getKey());
                    }
                }
            }
        }

        return result;
    }

    private static List<BedrockGeometry.Bone> orderedByDepth(BedrockGeometry geometry, Map<String, BedrockGeometry.Bone> bonesByName) {
        List<BedrockGeometry.Bone> ordered = new ArrayList<>(geometry.bones());
        ordered.sort((a, b) -> Integer.compare(depth(a, bonesByName), depth(b, bonesByName)));
        return ordered;
    }

    private static String rootAncestor(BedrockGeometry.Bone bone, Map<String, BedrockGeometry.Bone> bonesByName) {
        BedrockGeometry.Bone current = bone;
        int guard = 0;

        while (current.parent() != null && bonesByName.containsKey(current.parent()) && guard++ < 64) {
            current = bonesByName.get(current.parent());
        }

        return current.name();
    }

    private static int depth(BedrockGeometry.Bone bone, Map<String, BedrockGeometry.Bone> bonesByName) {
        int depth = 0;
        BedrockGeometry.Bone current = bone;

        while (current.parent() != null && bonesByName.containsKey(current.parent()) && depth < 64) {
            current = bonesByName.get(current.parent());
            depth++;
        }

        return depth;
    }

    /**
     * Where the chain continues from this bone: toward the next physics
     * child if there is one, otherwise toward the centroid of the bone's
     * own cubes. Scaled model space, relative to the bone pivot.
     */
    private static Vector3f restOffset(BedrockGeometry.Bone bone, Set<String> candidates,
                                       Map<String, List<BedrockGeometry.Bone>> children,
                                       Map<String, Matrix4f> boneWorld,
                                       float widthScale, float heightScale, Vector3f pivot) {
        for (BedrockGeometry.Bone child : children.getOrDefault(bone.name(), List.of())) {
            if (candidates.contains(child.name())) {
                Vector3f childPivot = worldPivot(child, boneWorld).mul(widthScale, heightScale, widthScale);
                Vector3f offset = childPivot.sub(pivot, new Vector3f());

                if (offset.length() >= 0.02F) {
                    return offset;
                }
            }
        }

        if (!bone.cubes().isEmpty()) {
            Vector3f centroid = new Vector3f();
            Matrix4f world = boneWorld.get(bone.name());

            for (BedrockGeometry.Cube cube : bone.cubes()) {
                float cx = -(cube.origin()[0] + cube.size()[0] / 2.0F) / 16.0F;
                float cy = (cube.origin()[1] + cube.size()[1] / 2.0F) / 16.0F;
                float cz = (cube.origin()[2] + cube.size()[2] / 2.0F) / 16.0F;
                centroid.add(world.transformPosition(new Vector3f(cx, cy, cz)));
            }

            centroid.div(bone.cubes().size());
            centroid.mul(widthScale, heightScale, widthScale);
            return centroid.sub(pivot);
        }

        return new Vector3f();
    }

    private static Vector3f worldPivot(BedrockGeometry.Bone bone, Map<String, Matrix4f> boneWorld) {
        Vector3f pivot = new Vector3f(-bone.pivot()[0] / 16.0F, bone.pivot()[1] / 16.0F, bone.pivot()[2] / 16.0F);
        return boneWorld.get(bone.name()).transformPosition(pivot);
    }

    private static void readNames(JsonObject physics, String key, Set<String> target) {
        if (physics.has(key) && physics.get(key).isJsonArray()) {
            for (JsonElement item : physics.getAsJsonArray(key)) {
                try {
                    target.add(item.getAsString());
                } catch (Exception ignored) {
                }
            }
        }
    }
}
