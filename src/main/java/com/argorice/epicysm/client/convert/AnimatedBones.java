package com.argorice.epicysm.client.convert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
 * The bones of a readable model that keep playing the model's own
 * animation while Epic Fight has the body: tails, ears, wings, hair,
 * accessories - anything the author keyframed in the everyday clips that
 * Epic Fight has no joint for. Each becomes a joint of its own appended to
 * the converted armature, so its cubes can move independently of the body
 * part they hang from.
 */
public final class AnimatedBones {
    private static final int MAX_JOINTS = 160;

    /**
     * One animated joint: appended under parentName (an Epic Fight joint or
     * an earlier animated bone) at the bone's pivot, in scaled model space.
     */
    public record Def(String boneName, int id, String parentName, int parentId, Vector3f worldPivot) {
    }

    /** As the runtime needs it: ids into the pose array, parents first. */
    public record Baked(String boneName, int id, int parentId, float widthScale, float heightScale) {
    }

    private AnimatedBones() {
    }

    /**
     * @param moving   bones that move in the played clips (see {@link OwnAnimation#movingBones})
     * @param excluded bones that must not be animated here (physics bones and what hangs under them)
     * @param firstId  the id of the first joint to hand out
     */
    public static List<Def> collect(BedrockGeometry geometry, JointMapper mapper, Set<String> moving,
                                    JsonObject overrides, Map<String, BedrockGeometry.Bone> bonesByName,
                                    Map<String, Matrix4f> boneWorld, String mainRoot,
                                    float widthScale, float heightScale, Set<String> excluded, int firstId) {
        Set<String> added = new HashSet<>();
        Set<String> removed = new HashSet<>();

        if (overrides != null && overrides.has("animation") && overrides.get("animation").isJsonObject()) {
            JsonObject animation = overrides.getAsJsonObject("animation");
            readNames(animation, "add", added);
            readNames(animation, "remove", removed);
        }

        Map<String, List<BedrockGeometry.Bone>> children = new HashMap<>();

        for (BedrockGeometry.Bone bone : geometry.bones()) {
            if (bone.parent() != null) {
                children.computeIfAbsent(bone.parent(), key -> new ArrayList<>()).add(bone);
            }
        }

        // The bones the body is built on: the ones an Epic Fight joint takes
        // its pivot from, and the ones with the names of body parts. They
        // and everything that wraps them belong to Epic Fight.
        Set<String> bodyBones = new HashSet<>();

        for (String jointName : JointMapper.JOINT_NAMES) {
            BedrockGeometry.Bone source = mapper.pivotSource(jointName);

            if (source != null) {
                bodyBones.add(source.name());
            }
        }

        for (BedrockGeometry.Bone bone : geometry.bones()) {
            if (JointMapper.isCoreBoneName(bone.name().toLowerCase(Locale.ROOT))) {
                bodyBones.add(bone.name());
            }
        }

        Map<String, Boolean> wrapsBody = new HashMap<>();
        Set<String> candidates = new LinkedHashSet<>();

        for (BedrockGeometry.Bone bone : orderedByDepth(geometry, bonesByName)) {
            String name = bone.name();

            if (removed.contains(name) || excluded.contains(name)) {
                continue;
            }

            if (mainRoot != null && !mainRoot.equals(rootAncestor(bone, bonesByName))) {
                continue; // stand-alone prop trees are not part of the body
            }

            if (mapper.jointFor(bone) == null) {
                continue; // hidden
            }

            if (bodyBones.contains(name) || wrapsBody(bone, children, bodyBones, wrapsBody)) {
                continue;
            }

            if (name.toLowerCase(Locale.ROOT).contains("locator") || !hasGeometry(bone, children)) {
                continue;
            }

            if (!(moving.contains(name) || added.contains(name))) {
                continue;
            }

            candidates.add(name);
        }

        List<Def> defs = new ArrayList<>();
        Map<String, Integer> idByBone = new HashMap<>();

        for (BedrockGeometry.Bone bone : orderedByDepth(geometry, bonesByName)) {
            if (!candidates.contains(bone.name()) || defs.size() >= MAX_JOINTS) {
                continue;
            }

            // The nearest animated ancestor, else the Epic Fight joint the
            // bone's cubes would otherwise be welded to.
            String parentName = null;
            int parentId = -1;

            for (BedrockGeometry.Bone above = bone.parent() != null ? bonesByName.get(bone.parent()) : null;
                    above != null; above = above.parent() != null ? bonesByName.get(above.parent()) : null) {
                Integer id = idByBone.get(above.name());

                if (id != null) {
                    parentName = above.name();
                    parentId = id;
                    break;
                }
            }

            if (parentName == null) {
                String jointName = mapper.jointFor(bone);

                if (jointName == null) {
                    continue;
                }

                parentName = jointName;
                parentId = JointMapper.jointId(jointName);
            }

            Vector3f pivot = worldPivot(bone, boneWorld).mul(widthScale, heightScale, widthScale);
            int id = firstId + defs.size();
            idByBone.put(bone.name(), id);
            defs.add(new Def(bone.name(), id, parentName, parentId, pivot));
        }

        return defs;
    }

    /** True when a body bone sits somewhere below this one. */
    private static boolean wrapsBody(BedrockGeometry.Bone bone, Map<String, List<BedrockGeometry.Bone>> children,
                                     Set<String> bodyBones, Map<String, Boolean> cache) {
        Boolean cached = cache.get(bone.name());

        if (cached != null) {
            return cached;
        }

        boolean wraps = false;

        for (BedrockGeometry.Bone child : children.getOrDefault(bone.name(), List.of())) {
            if (bodyBones.contains(child.name()) || wrapsBody(child, children, bodyBones, cache)) {
                wraps = true;
                break;
            }
        }

        cache.put(bone.name(), wraps);
        return wraps;
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

    private static Vector3f worldPivot(BedrockGeometry.Bone bone, Map<String, Matrix4f> boneWorld) {
        Vector3f pivot = new Vector3f(-bone.pivot()[0] / 16.0F, bone.pivot()[1] / 16.0F, bone.pivot()[2] / 16.0F);
        return boneWorld.get(bone.name()).transformPosition(pivot);
    }

    private static void readNames(JsonObject object, String key, Set<String> target) {
        if (object.has(key) && object.get(key).isJsonArray()) {
            for (JsonElement item : object.getAsJsonArray(key)) {
                try {
                    target.add(item.getAsString());
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /** Bones under a physics bone follow it; they cannot also be animated. */
    public static Set<String> underAny(Set<String> roots, BedrockGeometry geometry, Map<String, BedrockGeometry.Bone> bonesByName) {
        Set<String> result = new HashSet<>(roots);

        for (BedrockGeometry.Bone bone : geometry.bones()) {
            for (BedrockGeometry.Bone above = bone.parent() != null ? bonesByName.get(bone.parent()) : null;
                    above != null; above = above.parent() != null ? bonesByName.get(above.parent()) : null) {
                if (roots.contains(above.name())) {
                    result.add(bone.name());
                    break;
                }
            }
        }

        return result;
    }
}
