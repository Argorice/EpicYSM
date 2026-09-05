package com.argorice.epicysm.client.convert;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.Direction;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.client.model.Mesh;
import yesman.epicfight.api.client.model.MeshPartDefinition;
import yesman.epicfight.api.client.model.VertexBuilder;
import yesman.epicfight.api.client.model.transformer.HumanoidModelTransformer.PartTransformer;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.gameasset.Armatures;
import yesman.epicfight.model.armature.HumanoidArmature;

import com.argorice.epicysm.EpicYsm;
import com.argorice.epicysm.client.model.BedrockGeometry;
import com.argorice.epicysm.client.model.BedrockGeometryParser;
import com.argorice.epicysm.client.model.YsmModelSource;

/**
 * Converts a YSM Bedrock-geometry model into an Epic Fight skinned mesh and
 * a matching armature.
 */
public final class ModelConverter {
    private ModelConverter() {
    }

    public record Result(HumanoidMesh mesh, HumanoidArmature armature, String displayName, boolean allCutout,
                         List<PhysicsChains.Baked> physicsJoints,
                         List<AnimatedBones.Baked> animatedJoints, OwnAnimation animation) {
    }

    /** A perfectly good model that simply is not shaped like a person. */
    public static final class NotHumanoid extends IOException {
        public NotHumanoid(String message) {
            super(message);
        }
    }

    public static Result convert(String id, YsmModelSource source) throws IOException {
        return convert(id, source, null);
    }

    /**
     * @param measuredScale the size Yes Steve Model was actually seen drawing
     *                      this model at, as {width, height}, or null when it
     *                      has not been measured yet.
     */
    public static Result convert(String id, YsmModelSource source, @Nullable float[] measuredScale) throws IOException {
        BedrockGeometry geometry = BedrockGeometryParser.parse(source.readMainModel());
        JsonObject overrides = source.readOverrides();
        JointMapper mapper = new JointMapper(geometry, overrides);
        List<JsonObject> animationFiles = source.readAnimationFiles();

        // Bones the model itself keeps invisible by default (props shown
        // only during specific animations, conditional overlays) stay
        // hidden in the converted mesh too.
        java.util.Set<String> defaultHidden = AnimationHiddenBones.collect(animationFiles, source.readControllerFiles());

        // And the weapons the character was modelled carrying. Epic Fight
        // draws the item that is really in hand and swings that one, so a
        if (com.argorice.epicysm.client.ysm.WeaponBones.hideOpen()) {
            java.util.Set<String> carried = new java.util.LinkedHashSet<>();

            for (BedrockGeometry.Bone bone : geometry.bones()) {
                if (com.argorice.epicysm.client.ysm.WeaponBones.isWeapon(bone.name())) {
                    carried.add(bone.name());
                }
            }

            if (!carried.isEmpty()) {
                EpicYsm.LOGGER.info("Model {}: putting away {} weapon(s) the model carries itself: {}",
                        id, carried.size(), carried);
                defaultHidden.addAll(carried);
            }
        }

        if (!defaultHidden.isEmpty()) {
            com.argorice.epicysm.client.Diag.info("Model {}: hiding {} default-hidden bone(s): {}", id, defaultHidden.size(), defaultHidden);
            mapper.autoHide(defaultHidden);
        }

        Map<String, BedrockGeometry.Bone> bonesByName = new HashMap<>();

        for (BedrockGeometry.Bone bone : geometry.bones()) {
            bonesByName.put(bone.name(), bone);
        }

        Map<String, Matrix4f> boneWorld = new HashMap<>();

        for (BedrockGeometry.Bone bone : geometry.bones()) {
            computeBoneWorld(bone, bonesByName, boneWorld);
        }

        String displayName = id;
        boolean allCutout = false;
        JsonObject metadata = source.readMetadata();

        if (metadata != null) {
            if (metadata.has("metadata") && metadata.getAsJsonObject("metadata").has("name")) {
                displayName = metadata.getAsJsonObject("metadata").get("name").getAsString();
            }

            if (metadata.has("properties") && metadata.getAsJsonObject("properties").has("all_cutout")) {
                allCutout = metadata.getAsJsonObject("properties").get("all_cutout").getAsBoolean();
            }
        }

        float[] scales = computeRenderScale(id, metadata, overrides, mapper, boneWorld, measuredScale);
        String mainRoot = mainRootName(mapper, bonesByName);

        // Secondary-motion joints: hair, tails, skirts and similar swing
        // freely instead of being welded to the body. Switched off, they
        // are welded like everything else.
        java.util.Set<String> physicsBones = com.argorice.epicysm.client.EpicYsmConfig.physics()
                ? PhysicsChains.candidates(geometry, mapper, animationFiles, overrides, bonesByName, mainRoot)
                : java.util.Set.of();

        // Bones the model animates itself - a tail, ears, wings, hair - go
        // on playing the model's own clips while Epic Fight has the body.
        // A bone that swings by physics does not also play an animation.
        java.util.Set<String> moving = com.argorice.epicysm.client.EpicYsmConfig.ownAnimations()
                ? OwnAnimation.movingBones(animationFiles)
                : java.util.Set.of();
        List<AnimatedBones.Def> animatedDefs = !com.argorice.epicysm.client.EpicYsmConfig.ownAnimations()
                || (moving.isEmpty() && (overrides == null || !overrides.has("animation")))
                ? List.of()
                : AnimatedBones.collect(geometry, mapper, moving, overrides, bonesByName, boneWorld, mainRoot,
                        scales[0], scales[1], AnimatedBones.underAny(physicsBones, geometry, bonesByName),
                        PhysicsChains.FIRST_PHYSICS_JOINT_ID);
        Map<String, Integer> animatedIdByBone = new HashMap<>();

        for (AnimatedBones.Def def : animatedDefs) {
            animatedIdByBone.put(def.boneName(), def.id());
        }

        List<PhysicsChains.Def> physicsDefs = physicsBones.isEmpty()
                ? List.of()
                : PhysicsChains.collect(geometry, mapper, physicsBones, bonesByName, boneWorld, scales[0], scales[1],
                        animatedIdByBone, PhysicsChains.FIRST_PHYSICS_JOINT_ID + animatedDefs.size());

        if (!physicsDefs.isEmpty()) {
            com.argorice.epicysm.client.Diag.info("Model {}: {} physics joint(s): {}", id, physicsDefs.size(),
                    physicsDefs.stream().map(PhysicsChains.Def::boneName).toList());
        }

        OwnAnimation animation = animatedDefs.isEmpty()
                ? OwnAnimation.empty()
                : OwnAnimation.read(animationFiles, animatedIdByBone.keySet());

        if (!animatedDefs.isEmpty()) {
            EpicYsm.LOGGER.info("Model {}: {} bone(s) keep the model's own animation in battle: {}; clips: {}", id,
                    animatedDefs.size(), animatedDefs.stream().map(AnimatedBones.Def::boneName).toList(),
                    animation.describe());
        }

        // The armature is built first on purpose: it decides which extra
        // joints actually exist, and only those may own mesh cubes. Binding
        List<PhysicsChains.Baked> baked = new ArrayList<>();
        List<AnimatedBones.Baked> bakedAnimated = new ArrayList<>();
        Map<String, Integer> extraIdByBone = new HashMap<>();
        Map<String, Vector3f> jointPositions = new HashMap<>();
        List<String> guessedJoints = new ArrayList<>();
        List<String> repairedJoints = new ArrayList<>();
        HumanoidArmature armature = buildArmature(id, mapper, boneWorld, scales[0], scales[1],
                animatedDefs, bakedAnimated, physicsDefs, baked, extraIdByBone, jointPositions, guessedJoints, repairedJoints);
        refuseIfNotHumanoid(id, guessedJoints, repairedJoints);
        HumanoidMesh mesh = bakeMesh(id, geometry, mapper, bonesByName, mainRoot, boneWorld, extraIdByBone,
                jointPositions, scales[0], scales[1]);

        return new Result(mesh, armature, displayName, allCutout, baked, bakedAnimated, animation);
    }

    /** Stops before drawing a model that has no body to put a skeleton on. */
    private static void refuseIfNotHumanoid(String id, List<String> guessed, List<String> repaired) throws IOException {
        java.util.Set<String> guessedSet = new java.util.HashSet<>(guessed);
        int missingCore = 0;

        for (String core : new String[] { "Head", "Chest", "Torso", "Thigh_L", "Thigh_R", "Arm_L", "Arm_R" }) {
            if (guessedSet.contains(core)) {
                missingCore++;
            }
        }

        if (guessedSet.size() < 6 && new java.util.HashSet<>(repaired).size() < 8 && missingCore < 2) {
            return;
        }

        throw new NotHumanoid("model " + id + " has no humanoid skeleton (" + guessedSet.size()
                + " joint(s) had no bone to sit on, " + new java.util.HashSet<>(repaired).size()
                + " had to be moved, " + missingCore + " of the seven core joints missing);"
                + " leaving it to Yes Steve Model rather than tearing it apart");
    }

    /** The scale YSM would render the model with, as {width, height}. */
    private static float[] computeRenderScale(String id, @Nullable JsonObject metadata, @Nullable JsonObject overrides,
                                              JointMapper mapper, Map<String, Matrix4f> boneWorld,
                                              @Nullable float[] measuredScale) {
        float heightScale = Float.NaN;
        float widthScale = Float.NaN;

        // Nothing beats having watched Yes Steve Model draw it.
        if (measuredScale != null && measuredScale.length == 2) {
            widthScale = measuredScale[0];
            heightScale = measuredScale[1];
            com.argorice.epicysm.client.Diag.info("Model {}: built at the size Yes Steve Model was measured drawing it,"
                    + " {} wide and {} tall", id, widthScale, heightScale);
        }

        if (Float.isNaN(heightScale) && metadata != null && metadata.has("properties")) {
            JsonObject properties = metadata.getAsJsonObject("properties");

            if (properties.has("height_scale")) {
                heightScale = properties.get("height_scale").getAsFloat();
            }

            if (properties.has("width_scale")) {
                widthScale = properties.get("width_scale").getAsFloat();
            }
        }

        if (Float.isNaN(heightScale)) {
            // Yes Steve Model's own default, and it is not a guess: of the
            // models it ships, vanilla Steve and Alex write "height_scale":1
            heightScale = 0.7F;
        }

        if (Float.isNaN(widthScale)) {
            widthScale = heightScale;
        }

        if (overrides != null && overrides.has("scale")) {
            float multiplier = overrides.get("scale").getAsFloat();
            heightScale *= multiplier;
            widthScale *= multiplier;
        }

        heightScale = Math.max(0.05F, Math.min(4.0F, heightScale));
        widthScale = Math.max(0.05F, Math.min(4.0F, widthScale));

        return new float[] { widthScale, heightScale };
    }

    /**
     * The top-level ancestor of the skeleton the biped joints map to. Bones
     * under any other top-level ancestor are stand-alone props.
     */
    @Nullable
    private static String mainRootName(JointMapper mapper, Map<String, BedrockGeometry.Bone> bonesByName) {
        for (String jointName : new String[] { "Root", "Torso", "Head" }) {
            BedrockGeometry.Bone anchor = mapper.pivotSource(jointName);

            if (anchor != null) {
                return rootAncestorName(anchor, bonesByName);
            }
        }

        return null;
    }

    /** The extra joint (physics or animated) owning this bone's cubes: the nearest such ancestor. */
    @Nullable
    private static Integer extraJointFor(BedrockGeometry.Bone bone, Map<String, BedrockGeometry.Bone> bonesByName,
                                         Map<String, Integer> extraIdByBone) {
        BedrockGeometry.Bone current = bone;

        while (current != null) {
            Integer id = extraIdByBone.get(current.name());

            if (id != null) {
                return id;
            }

            current = current.parent() != null ? bonesByName.get(current.parent()) : null;
        }

        return null;
    }

    private static String rootAncestorName(BedrockGeometry.Bone bone, Map<String, BedrockGeometry.Bone> bonesByName) {
        BedrockGeometry.Bone current = bone;

        while (current.parent() != null) {
            BedrockGeometry.Bone parent = bonesByName.get(current.parent());

            if (parent == null) {
                break;
            }

            current = parent;
        }

        return current.name();
    }

    /* ---------------------------------------------------------------------
     * Bone transforms
     * ------------------------------------------------------------------- */

    private static Matrix4f computeBoneWorld(BedrockGeometry.Bone bone, Map<String, BedrockGeometry.Bone> bonesByName, Map<String, Matrix4f> cache) {
        Matrix4f cached = cache.get(bone.name());

        if (cached != null) {
            return cached;
        }

        Matrix4f parentWorld = new Matrix4f();

        if (bone.parent() != null) {
            BedrockGeometry.Bone parent = bonesByName.get(bone.parent());

            if (parent != null) {
                parentWorld = computeBoneWorld(parent, bonesByName, cache);
            }
        }

        Matrix4f world = new Matrix4f(parentWorld).mul(localBoneMatrix(bone));
        cache.put(bone.name(), world);
        return world;
    }

    /** Bind-pose local matrix of a bone, geckolib-style. */
    private static Matrix4f localBoneMatrix(BedrockGeometry.Bone bone) {
        float px = -bone.pivot()[0] / 16.0F;
        float py = bone.pivot()[1] / 16.0F;
        float pz = bone.pivot()[2] / 16.0F;
        float rx = (float) Math.toRadians(-bone.rotation()[0]);
        float ry = (float) Math.toRadians(-bone.rotation()[1]);
        float rz = (float) Math.toRadians(bone.rotation()[2]);

        return new Matrix4f()
                .translate(px, py, pz)
                .rotateZ(rz)
                .rotateY(ry)
                .rotateX(rx)
                .translate(-px, -py, -pz);
    }

    /** World-space pivot point of a bone in bind pose. */
    private static Vector3f worldPivot(BedrockGeometry.Bone bone, Map<String, Matrix4f> boneWorld) {
        Vector3f pivot = new Vector3f(-bone.pivot()[0] / 16.0F, bone.pivot()[1] / 16.0F, bone.pivot()[2] / 16.0F);
        return boneWorld.get(bone.name()).transformPosition(pivot);
    }

    /* ---------------------------------------------------------------------
     * Mesh baking
     * ------------------------------------------------------------------- */

    /** One baked vertex; turned into the mesh arrays at assembly time. */
    private record BakedVertex(float x, float y, float z, float nx, float ny, float nz, float u, float v, int jointId) {
    }

    private static final Direction[] QUAD_ORDER = {
            Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.UP, Direction.DOWN
    };

    private static HumanoidMesh bakeMesh(String id, BedrockGeometry geometry, JointMapper mapper,
                                         Map<String, BedrockGeometry.Bone> bonesByName,
                                         @Nullable String mainRoot, Map<String, Matrix4f> boneWorld,
                                         Map<String, Integer> extraIdByBone,
                                         Map<String, Vector3f> jointPositions,
                                         float widthScale, float heightScale) {
        List<String> retargeted = new ArrayList<>();
        List<BakedVertex> vertices = new ArrayList<>();
        Map<MeshPartDefinition, IntList> indices = new HashMap<>();
        PartTransformer.IndexCounter indexCounter = new PartTransformer.IndexCounter();
        Map<String, MeshPartDefinition> partDefinitions = new HashMap<>();

        for (BedrockGeometry.Bone bone : geometry.bones()) {
            if (bone.cubes().isEmpty()) {
                continue;
            }

            // Free-standing prop trees (a lantern, a vehicle) live outside
            // the skeleton root and only appear in special animations.
            if (mainRoot != null && !mainRoot.equals(rootAncestorName(bone, bonesByName))) {
                continue;
            }

            String inherited = mapper.jointFor(bone);

            if (inherited == null) {
                continue; // hidden
            }

            boolean named = mapper.hasKnownName(bone);

            // Cubes under a physics or an animated bone follow that joint
            // instead of the rigid body joint, so they can move.
            Integer physicsId = extraJointFor(bone, bonesByName, extraIdByBone);
            Matrix4f world = boneWorld.get(bone.name());

            for (BedrockGeometry.Cube cube : bone.cubes()) {
                // Where the cube itself ends up, not where the bone's pivot
                // is: a bone that never rotates often keeps its pivot at the
                Vector3f center = cubeCenter(cube, bone, world, widthScale, heightScale);
                String jointName = placeCube(inherited, named, center, jointPositions, retargeted, bone.name());
                int jointId = physicsId != null ? physicsId : JointMapper.jointId(jointName);
                MeshPartDefinition partDefinition = partDefinitions.computeIfAbsent(
                        JointMapper.partNameFor(jointName), SimplePartDefinition::new);
                bakeCube(cube, bone, world, geometry, jointId, partDefinition, vertices, indices, indexCounter, widthScale, heightScale);
            }
        }

        if (!retargeted.isEmpty()) {
            com.argorice.epicysm.client.Diag.info("Model {}: {} trailing bone(s) hang too far from the joint their name points at"
                    + " and were bound to the nearest body joint instead (hair and skirts stop swinging from"
                    + " the neck): {}", id, retargeted.size(),
                    retargeted.size() > 12 ? retargeted.subList(0, 12) + " ..." : retargeted);
        }

        return buildHumanoidMesh(vertices, indices, partDefinitions);
    }

    /** Spine joints, the only ones a named decoration may be moved between. */
    /** Every joint that owns a piece of the mesh; the choice for a prop. */
    private static final String[] ANY_JOINTS = {
            "Root", "Torso", "Chest", "Head", "Arm_L", "Arm_R", "Hand_L", "Hand_R",
            "Thigh_L", "Thigh_R", "Leg_L", "Leg_R"
    };

    /** Decides which joint a single cube should move with. */
    private static String placeCube(String inherited, boolean named, Vector3f center,
                                    Map<String, Vector3f> jointPositions, List<String> retargeted, String boneName) {
        Vector3f head = jointPositions.get("Head");

        if (jointPositions.isEmpty() || head == null || head.y() < 0.01F || !center.isFinite()) {
            return inherited;
        }

        if (!named) {
            String best = nearestOf(ANY_JOINTS, center, jointPositions, inherited);

            if (!best.equals(inherited)) {
                note(retargeted, boneName, inherited, best);
            }

            return best;
        }

        // A bone that says which part of the body it is gets to be believed,
        // however far its cubes reach. Hair down to the knees is still hair:
        return inherited;
    }

    private static void note(List<String> retargeted, String boneName, String from, String to) {
        String entry = boneName + " " + from + ">" + to;

        if (!retargeted.contains(entry)) {
            retargeted.add(entry);
        }
    }

    private static String nearestOf(String[] candidates, Vector3f center,
                                    Map<String, Vector3f> jointPositions, String fallback) {
        String best = fallback;
        float bestDistance = Float.MAX_VALUE;

        for (String candidate : candidates) {
            Vector3f position = jointPositions.get(candidate);

            if (position == null) {
                continue;
            }

            float distance = center.distance(position);

            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        return best;
    }

    /** Where a cube sits once its bone and its own rotation are applied. */
    private static Vector3f cubeCenter(BedrockGeometry.Cube cube, BedrockGeometry.Bone bone, Matrix4f boneMatrix,
                                       float widthScale, float heightScale) {
        float cpx = -cube.pivot()[0] / 16.0F;
        float cpy = cube.pivot()[1] / 16.0F;
        float cpz = cube.pivot()[2] / 16.0F;

        Matrix4f matrix = new Matrix4f(boneMatrix)
                .translate(cpx, cpy, cpz)
                .rotateZ((float) Math.toRadians(cube.rotation()[2]))
                .rotateY((float) Math.toRadians(-cube.rotation()[1]))
                .rotateX((float) Math.toRadians(-cube.rotation()[0]))
                .translate(-cpx, -cpy, -cpz);

        Vector3f center = matrix.transformPosition(new Vector3f(
                -(cube.origin()[0] + cube.size()[0] * 0.5F) / 16.0F,
                (cube.origin()[1] + cube.size()[1] * 0.5F) / 16.0F,
                (cube.origin()[2] + cube.size()[2] * 0.5F) / 16.0F));
        return center.mul(widthScale, heightScale, widthScale);
    }

    private static void bakeCube(BedrockGeometry.Cube cube, BedrockGeometry.Bone bone, Matrix4f boneMatrix, BedrockGeometry geometry,
                                 int jointId, MeshPartDefinition partDefinition, List<BakedVertex> vertices,
                                 Map<MeshPartDefinition, IntList> indices, PartTransformer.IndexCounter indexCounter,
                                 float widthScale, float heightScale) {
        float inflate = (cube.hasInflate() ? cube.inflate() : bone.inflate()) / 16.0F;
        boolean mirror = cube.mirror() == Boolean.TRUE;

        float cpx = -cube.pivot()[0] / 16.0F;
        float cpy = cube.pivot()[1] / 16.0F;
        float cpz = cube.pivot()[2] / 16.0F;
        float crx = (float) Math.toRadians(-cube.rotation()[0]);
        float cry = (float) Math.toRadians(-cube.rotation()[1]);
        float crz = (float) Math.toRadians(cube.rotation()[2]);

        Matrix4f matrix = new Matrix4f(boneMatrix)
                .translate(cpx, cpy, cpz)
                .rotateZ(crz)
                .rotateY(cry)
                .rotateX(crx)
                .translate(-cpx, -cpy, -cpz);

        float sx = cube.size()[0] / 16.0F;
        float sy = cube.size()[1] / 16.0F;
        float sz = cube.size()[2] / 16.0F;
        float ox = -(cube.origin()[0] + cube.size()[0]) / 16.0F;
        float oy = cube.origin()[1] / 16.0F;
        float oz = cube.origin()[2] / 16.0F;

        Vector3f[] corners = corners(ox, oy, oz, sx, sy, sz, inflate);

        for (Direction direction : QUAD_ORDER) {
            float[][] quadUvs = buildQuadUvs(cube, geometry, direction, mirror);

            if (quadUvs == null) {
                continue;
            }

            Vector3f[] quadCorners = verticesForQuad(corners, direction, cube.boxUv() != null, mirror);
            Vector3f normal = new Vector3f(direction.step());

            if (mirror) {
                normal.mul(-1.0F, 1.0F, 1.0F);
            }

            Vector3f worldNormal = matrix.transformDirection(new Vector3f(normal));
            // Non-uniform scale transforms normals by the inverse transpose.
            worldNormal.set(worldNormal.x() / widthScale, worldNormal.y() / heightScale, worldNormal.z() / widthScale).normalize();

            for (int i = 0; i < 4; i++) {
                Vector3f position = matrix.transformPosition(new Vector3f(quadCorners[i]));
                vertices.add(new BakedVertex(position.x() * widthScale, position.y() * heightScale, position.z() * widthScale,
                        worldNormal.x(), worldNormal.y(), worldNormal.z(),
                        quadUvs[i][0], quadUvs[i][1], jointId));
            }

            PartTransformer.triangluatePolygon(indices, partDefinition, indexCounter);
        }
    }

    /**
     * The eight cube corners, indexed like geckolib's VertexSet:
     * 0 bottomLeftBack, 1 bottomRightBack, 2 topLeftBack, 3 topRightBack,
     * 4 topLeftFront, 5 topRightFront, 6 bottomLeftFront, 7 bottomRightFront.
     */
    private static Vector3f[] corners(float ox, float oy, float oz, float sx, float sy, float sz, float inflate) {
        float x0 = ox - inflate;
        float y0 = oy - inflate;
        float z0 = oz - inflate;
        float x1 = ox + sx + inflate;
        float y1 = oy + sy + inflate;
        float z1 = oz + sz + inflate;

        return new Vector3f[] {
                new Vector3f(x0, y0, z0),
                new Vector3f(x0, y0, z1),
                new Vector3f(x0, y1, z0),
                new Vector3f(x0, y1, z1),
                new Vector3f(x1, y1, z0),
                new Vector3f(x1, y1, z1),
                new Vector3f(x1, y0, z0),
                new Vector3f(x1, y0, z1)
        };
    }

    private static Vector3f[] pick(Vector3f[] c, int a, int b, int d, int e) {
        return new Vector3f[] { c[a], c[b], c[d], c[e] };
    }

    private static Vector3f[] verticesForQuad(Vector3f[] c, Direction direction, boolean boxUv, boolean mirror) {
        // Same corner tables as geckolib's VertexSet.
        Vector3f[] west = pick(c, 3, 2, 0, 1);
        Vector3f[] east = pick(c, 4, 5, 7, 6);
        Vector3f[] north = pick(c, 2, 4, 6, 0);
        Vector3f[] south = pick(c, 5, 3, 1, 7);
        Vector3f[] up = pick(c, 3, 5, 4, 2);
        Vector3f[] down = pick(c, 0, 6, 7, 1);

        return switch (direction) {
            case WEST -> mirror ? east : west;
            case EAST -> mirror ? west : east;
            case NORTH -> north;
            case SOUTH -> south;
            case UP -> mirror && !boxUv ? down : up;
            case DOWN -> mirror && !boxUv ? up : down;
        };
    }

    /**
     * UV coordinates for the four vertices of a quad, normalized, or null
     * when the cube uses per-face UVs and has no entry for this direction.
     * Follows geckolib's GeoQuad.build, including the horizontal flip that
     * Bedrock applies to non-mirrored cubes.
     */
    @Nullable
    private static float[][] buildQuadUvs(BedrockGeometry.Cube cube, BedrockGeometry geometry, Direction direction, boolean mirror) {
        float u;
        float v;
        float uSize;
        float vSize;
        int uvRotation = 0;

        if (cube.boxUv() != null) {
            float[] uv = cube.boxUv();
            float zs = (float) Math.floor(cube.size()[2]);
            float xs = (float) Math.floor(cube.size()[0]);
            float ys = (float) Math.floor(cube.size()[1]);

            switch (direction) {
                case WEST -> { u = uv[0] + zs + xs; v = uv[1] + zs; uSize = zs; vSize = ys; }
                case EAST -> { u = uv[0]; v = uv[1] + zs; uSize = zs; vSize = ys; }
                case NORTH -> { u = uv[0] + zs; v = uv[1] + zs; uSize = xs; vSize = ys; }
                case SOUTH -> { u = uv[0] + zs + xs + zs; v = uv[1] + zs; uSize = xs; vSize = ys; }
                case UP -> { u = uv[0] + zs; v = uv[1]; uSize = xs; vSize = zs; }
                case DOWN -> { u = uv[0] + zs + xs; v = uv[1] + zs; uSize = xs; vSize = -zs; }
                default -> { return null; }
            }
        } else if (cube.faces() != null) {
            BedrockGeometry.Face face = cube.faces().get(direction.getName());

            if (face == null) {
                return null;
            }

            u = face.uv()[0];
            v = face.uv()[1];
            uSize = face.uvSize()[0];
            vSize = face.uvSize()[1];
            uvRotation = face.uvRotation();
        } else {
            return null;
        }

        float texWidth = geometry.textureWidth();
        float texHeight = geometry.textureHeight();
        float uLeft = u / texWidth;
        float uRight = (u + uSize) / texWidth;
        float vTop = v / texHeight;
        float vBottom = (v + vSize) / texHeight;

        if (!mirror) {
            float temp = uRight;
            uRight = uLeft;
            uLeft = temp;
        }

        return rotateUvs(uLeft, vTop, uRight, vBottom, uvRotation);
    }

    private static float[][] rotateUvs(float u, float v, float uWidth, float vHeight, int rotationDegrees) {
        int rotation = ((rotationDegrees % 360) + 360) % 360;

        return switch (rotation / 90) {
            case 1 -> new float[][] { {uWidth, v}, {uWidth, vHeight}, {u, vHeight}, {u, v} };
            case 2 -> new float[][] { {uWidth, vHeight}, {u, vHeight}, {u, v}, {uWidth, v} };
            case 3 -> new float[][] { {u, vHeight}, {u, v}, {uWidth, v}, {uWidth, vHeight} };
            default -> new float[][] { {u, v}, {uWidth, v}, {uWidth, vHeight}, {u, vHeight} };
        };
    }

    /* ---------------------------------------------------------------------
     * Mesh assembly
     * ------------------------------------------------------------------- */

    private static final String[] HUMANOID_PART_NAMES = {
            "head", "torso", "leftArm", "rightArm", "leftLeg", "rightLeg",
            "hat", "jacket", "leftSleeve", "rightSleeve", "leftPants", "rightPants"
    };

    private static HumanoidMesh buildHumanoidMesh(List<BakedVertex> vertices, Map<MeshPartDefinition, IntList> indices,
                                                  Map<String, MeshPartDefinition> partDefinitions) {
        // Same array layout as SingleGroupVertexBuilder.loadVertexInformation:
        // every vertex has exactly one joint influence with weight 1.0, and
        // vindices stores (jointId, weightIndex) pairs.
        int count = vertices.size();
        Float[] positions = new Float[count * 3];
        Float[] normals = new Float[count * 3];
        Float[] uvs = new Float[count * 2];
        Integer[] vindices = new Integer[count * 2];
        Integer[] vcounts = new Integer[count];
        Float[] weights = { 1.0F };

        for (int i = 0; i < count; i++) {
            BakedVertex vertex = vertices.get(i);
            positions[i * 3] = vertex.x();
            positions[i * 3 + 1] = vertex.y();
            positions[i * 3 + 2] = vertex.z();
            normals[i * 3] = vertex.nx();
            normals[i * 3 + 1] = vertex.ny();
            normals[i * 3 + 2] = vertex.nz();
            uvs[i * 2] = vertex.u();
            uvs[i * 2 + 1] = vertex.v();
            vindices[i * 2] = vertex.jointId();
            vindices[i * 2 + 1] = 0;
            vcounts[i] = 1;
        }

        Map<String, Number[]> arrayMap = new HashMap<>();
        arrayMap.put("positions", positions);
        arrayMap.put("normals", normals);
        arrayMap.put("uvs", uvs);
        arrayMap.put("weights", weights);
        arrayMap.put("vcounts", vcounts);
        arrayMap.put("vindices", vindices);

        Map<MeshPartDefinition, List<VertexBuilder>> meshDefinitions = new HashMap<>();

        for (Map.Entry<MeshPartDefinition, IntList> entry : indices.entrySet()) {
            meshDefinitions.put(entry.getKey(), VertexBuilder.create(entry.getValue().toIntArray()));
        }

        for (String partName : HUMANOID_PART_NAMES) {
            MeshPartDefinition definition = partDefinitions.computeIfAbsent(partName, SimplePartDefinition::new);
            meshDefinitions.computeIfAbsent(definition, key -> new ArrayList<>());
        }

        return new HumanoidMesh(arrayMap, meshDefinitions, null, null);
    }

    /* ---------------------------------------------------------------------
     * Armature building
     * ------------------------------------------------------------------- */

    private static HumanoidArmature buildArmature(String id, JointMapper mapper, Map<String, Matrix4f> boneWorld,
                                                  float widthScale, float heightScale,
                                                  List<AnimatedBones.Def> animatedDefs, List<AnimatedBones.Baked> outAnimated,
                                                  List<PhysicsChains.Def> physicsDefs, List<PhysicsChains.Baked> outBaked,
                                                  Map<String, Integer> outExtraIds,
                                                  Map<String, Vector3f> outJointPositions,
                                                  List<String> outGuessed, List<String> outRepaired) {
        Armature biped = Armatures.BIPED.get();

        Map<String, OpenMatrix4f> bipedWorld = new HashMap<>();
        collectWorldTransforms(biped.rootJoint, new OpenMatrix4f(), bipedWorld);

        // Estimate the model's overall (post render-scale) proportions from
        // the head pivot height, to place joints the model has no bones for.
        float scale = 1.0F;
        BedrockGeometry.Bone headBone = mapper.pivotSource("Head");
        OpenMatrix4f bipedHeadWorld = bipedWorld.get("Head");

        if (headBone != null && bipedHeadWorld != null && bipedHeadWorld.m31 > 0.01F) {
            float modelHeadY = worldPivot(headBone, boneWorld).y() * heightScale;

            if (modelHeadY > 0.01F) {
                scale = Math.max(0.05F, Math.min(5.0F, modelHeadY / bipedHeadWorld.m31));
            }
        }

        Map<String, OpenMatrix4f> ourWorld = new HashMap<>();

        for (Map.Entry<String, OpenMatrix4f> entry : bipedWorld.entrySet()) {
            String jointName = entry.getKey();
            OpenMatrix4f bipedJointWorld = entry.getValue();
            BedrockGeometry.Bone source = mapper.pivotSource(jointName);
            float x;
            float y;
            float z;

            if (source != null && boneWorld.containsKey(source.name())) {
                Vector3f pivot = worldPivot(source, boneWorld);
                x = pivot.x() * widthScale;
                y = pivot.y() * heightScale;
                z = pivot.z() * widthScale;
            } else {
                outGuessed.add(jointName);
                x = bipedJointWorld.m30 * scale;
                y = bipedJointWorld.m31 * scale;
                z = bipedJointWorld.m32 * scale;
            }

            OpenMatrix4f rotation = new OpenMatrix4f(bipedJointWorld);
            rotation.m30 = 0.0F;
            rotation.m31 = 0.0F;
            rotation.m32 = 0.0F;

            OpenMatrix4f world = OpenMatrix4f.createTranslation(x, y, z);
            world.mulBack(rotation);
            ourWorld.put(jointName, world);
        }

        sanitizeJoints(id, ourWorld, bipedWorld, scale, outRepaired);
        placeBodyJoints(ourWorld);

        for (Map.Entry<String, OpenMatrix4f> entry : ourWorld.entrySet()) {
            OpenMatrix4f world = entry.getValue();
            outJointPositions.put(entry.getKey(), new Vector3f(world.m30, world.m31, world.m32));
        }

        Map<String, Joint> jointMap = new HashMap<>();
        Joint root = copyJoint(biped.rootJoint, null, ourWorld, jointMap);

        // Animated joints sit at the bone's pivot with no orientation of
        // their own: at rest their frame is model space, and posed it is
        // model space turned by whatever their parent did. That is the
        // frame the model's own animation was authored in.
        Map<String, Joint> extraJointsByBone = new HashMap<>();
        Map<String, OpenMatrix4f> extraWorldByBone = new HashMap<>();

        for (AnimatedBones.Def def : animatedDefs) {
            Joint parentJoint = extraJointsByBone.containsKey(def.parentName())
                    ? extraJointsByBone.get(def.parentName()) : jointMap.get(def.parentName());
            OpenMatrix4f parentWorld = extraWorldByBone.containsKey(def.parentName())
                    ? extraWorldByBone.get(def.parentName()) : ourWorld.get(def.parentName());

            if (parentJoint == null || parentWorld == null) {
                continue;
            }

            OpenMatrix4f world = OpenMatrix4f.createTranslation(def.worldPivot().x(), def.worldPivot().y(), def.worldPivot().z());
            OpenMatrix4f local = OpenMatrix4f.mul(OpenMatrix4f.invert(parentWorld, null), world, null);
            Joint joint = new Joint("epicysm_anim_" + def.boneName(), def.id(), local);
            parentJoint.addSubJoints(joint);
            jointMap.put(joint.getName(), joint);
            extraJointsByBone.put(def.boneName(), joint);
            extraWorldByBone.put(def.boneName(), world);
            outAnimated.add(new AnimatedBones.Baked(def.boneName(), def.id(), def.parentId(), widthScale, heightScale));
            outExtraIds.put(def.boneName(), def.id());
        }

        // Physics joints hang under their parent (an EF joint, an animated
        // joint or an earlier physics joint), inheriting the parent's bind
        // orientation so the rest offset is expressible in the joint's own
        // frame.
        Map<String, Joint> physicsJointsByBone = new HashMap<>();
        Map<String, OpenMatrix4f> physicsWorldByBone = new HashMap<>();

        for (PhysicsChains.Def def : physicsDefs) {
            OpenMatrix4f parentWorld;
            Joint parentJoint;
            boolean chainRoot = !physicsJointsByBone.containsKey(def.parentName());

            if (physicsJointsByBone.containsKey(def.parentName())) {
                parentJoint = physicsJointsByBone.get(def.parentName());
                parentWorld = physicsWorldByBone.get(def.parentName());
            } else if (extraJointsByBone.containsKey(def.parentName())) {
                parentJoint = extraJointsByBone.get(def.parentName());
                parentWorld = extraWorldByBone.get(def.parentName());
            } else {
                parentJoint = jointMap.get(def.parentName());
                parentWorld = ourWorld.get(def.parentName());
            }

            if (parentJoint == null || parentWorld == null) {
                continue;
            }

            OpenMatrix4f world = new OpenMatrix4f(parentWorld);
            world.m30 = def.worldPivot().x();
            world.m31 = def.worldPivot().y();
            world.m32 = def.worldPivot().z();

            OpenMatrix4f local = OpenMatrix4f.mul(OpenMatrix4f.invert(parentWorld, null), world, null);
            Joint joint = new Joint("epicysm_phys_" + def.boneName(), def.id(), local);
            parentJoint.addSubJoints(joint);
            jointMap.put(joint.getName(), joint);
            physicsJointsByBone.put(def.boneName(), joint);
            physicsWorldByBone.put(def.boneName(), world);

            // Rest offset into the joint's bind frame: R^T * offset.
            org.joml.Vector3f offset = def.restOffset();
            org.joml.Vector3f restLocal = new org.joml.Vector3f(
                    world.m00 * offset.x() + world.m01 * offset.y() + world.m02 * offset.z(),
                    world.m10 * offset.x() + world.m11 * offset.y() + world.m12 * offset.z(),
                    world.m20 * offset.x() + world.m21 * offset.y() + world.m22 * offset.z());
            outBaked.add(new PhysicsChains.Baked(def.id(), def.parentId(), restLocal, chainRoot));
            outExtraIds.put(def.boneName(), def.id());
        }

        // The pose array is indexed by joint id, so it must span the
        // highest id in use, not merely the number of joints.
        int jointCount = 0;

        for (Joint joint : jointMap.values()) {
            jointCount = Math.max(jointCount, joint.getId() + 1);
        }

        HumanoidArmature armature = new HumanoidArmature(EpicYsm.MODID + ":" + id, jointCount, root, jointMap);
        armature.bakeOriginMatrices();
        return armature;
    }

    /** Puts Root, Torso and the shoulders where Epic Fight expects them. */
    private static void placeBodyJoints(Map<String, OpenMatrix4f> ourWorld) {
        OpenMatrix4f thighLeft = ourWorld.get("Thigh_L");
        OpenMatrix4f thighRight = ourWorld.get("Thigh_R");
        OpenMatrix4f root = ourWorld.get("Root");
        OpenMatrix4f torso = ourWorld.get("Torso");
        OpenMatrix4f chest = ourWorld.get("Chest");

        if (thighLeft == null || thighRight == null || root == null || torso == null) {
            return;
        }

        float hipY = (thighLeft.m31 + thighRight.m31) * 0.5F;
        float hipZ = (thighLeft.m32 + thighRight.m32) * 0.5F;
        setPosition(root, 0.0F, hipY, hipZ);

        // Torso sits a seventh of the way from the hips to the chest, the
        // same share Epic Fight's own skeleton uses.
        if (chest != null) {
            setPosition(torso, 0.0F, hipY + (chest.m31 - hipY) * 0.143F, hipZ + (chest.m32 - hipZ) * 0.143F);
        } else {
            setPosition(torso, 0.0F, hipY, hipZ);
        }

        // Shoulders belong at the neck, where the head joint is, so that a
        // shoulder swing rotates the arm about the body and not about the
        // arm's own pivot.
        OpenMatrix4f head = ourWorld.get("Head");

        if (head != null) {
            for (String shoulder : new String[] { "Shoulder_L", "Shoulder_R" }) {
                OpenMatrix4f joint = ourWorld.get(shoulder);

                if (joint != null) {
                    setPosition(joint, 0.0F, head.m31, head.m32);
                }
            }
        }
    }

    /**
     * Throws out joint positions that cannot be right, and puts the plain
     * biped position there instead.
     */
    private static void sanitizeJoints(String id, Map<String, OpenMatrix4f> ourWorld,
                                       Map<String, OpenMatrix4f> bipedWorld, float scale, List<String> outRepaired) {
        OpenMatrix4f bipedHead = bipedWorld.get("Head");

        if (bipedHead == null || bipedHead.m31 < 0.01F) {
            return;
        }

        float expected = bipedHead.m31 * scale;

        if (!(expected > 0.01F)) {
            return;
        }

        java.util.Set<String> doubtful = new java.util.HashSet<>();

        for (Map.Entry<String, OpenMatrix4f> entry : ourWorld.entrySet()) {
            OpenMatrix4f reference = bipedWorld.get(entry.getKey());

            if (reference != null && implausible(entry.getValue(), reference, bipedHead.m31, expected)) {
                doubtful.add(entry.getKey());
            }
        }

        // A limb that does not match its mirror image is a mismatched bone
        // on one side; neither side can be trusted after that.
        for (String limb : new String[] { "Thigh", "Knee", "Leg", "Shoulder", "Arm", "Elbow", "Hand", "Tool" }) {
            OpenMatrix4f left = ourWorld.get(limb + "_L");
            OpenMatrix4f right = ourWorld.get(limb + "_R");

            if (left != null && right != null && Math.abs(left.m31 - right.m31) > 0.25F * expected) {
                doubtful.add(limb + "_L");
                doubtful.add(limb + "_R");
            }
        }

        // Repair whole limbs, never half of one. An arm whose shoulder was
        // put back on the plain biped while its elbow kept a position from
        for (String[] chain : JOINT_CHAINS) {
            boolean anyDoubtful = false;

            for (String joint : chain) {
                anyDoubtful |= doubtful.contains(joint);
            }

            if (!anyDoubtful) {
                continue;
            }

            for (String joint : chain) {
                OpenMatrix4f reference = bipedWorld.get(joint);
                OpenMatrix4f ours = ourWorld.get(joint);

                if (reference != null && ours != null) {
                    useBipedPosition(ours, reference, scale);
                    outRepaired.add(joint);
                }
            }
        }

        if (!outRepaired.isEmpty()) {
            com.argorice.epicysm.client.Diag.info("Model {}: {} joint(s) sat where no joint of a body can sit and were put back on"
                    + " the plain biped: {}", id, outRepaired.size(), outRepaired);
        }
    }

    /** The limbs, each repaired as a whole or not at all. */
    private static final String[][] JOINT_CHAINS = {
            { "Root", "Torso", "Chest", "Head" },
            { "Shoulder_L", "Arm_L", "Elbow_L", "Hand_L", "Tool_L" },
            { "Shoulder_R", "Arm_R", "Elbow_R", "Hand_R", "Tool_R" },
            { "Thigh_L", "Knee_L", "Leg_L" },
            { "Thigh_R", "Knee_R", "Leg_R" }
    };

    private static boolean implausible(OpenMatrix4f ours, OpenMatrix4f reference, float bipedHeadHeight, float expected) {
        if (!Float.isFinite(ours.m30) || !Float.isFinite(ours.m31) || !Float.isFinite(ours.m32)) {
            return true;
        }

        // Sideways and front-to-back, no joint of a body is anywhere near a
        // whole body height away from its middle.
        if (Math.abs(ours.m30) > 0.9F * expected || Math.abs(ours.m32) > 0.9F * expected) {
            return true;
        }

        // Height is judged as a share of this model's own head height, so
        // chibi and giant proportions pass and a knee at the neck does not.
        return Math.abs(ours.m31 / expected - reference.m31 / bipedHeadHeight) > 0.40F;
    }

    private static void useBipedPosition(OpenMatrix4f target, OpenMatrix4f reference, float scale) {
        setPosition(target, reference.m30 * scale, reference.m31 * scale, reference.m32 * scale);
    }

    private static void setPosition(OpenMatrix4f matrix, float x, float y, float z) {
        matrix.m30 = x;
        matrix.m31 = y;
        matrix.m32 = z;
    }

    private static void collectWorldTransforms(Joint joint, OpenMatrix4f parentWorld, Map<String, OpenMatrix4f> result) {
        OpenMatrix4f world = OpenMatrix4f.mul(parentWorld, joint.getLocalTransform(), null);
        result.put(joint.getName(), world);

        for (Joint child : joint.getSubJoints()) {
            collectWorldTransforms(child, world, result);
        }
    }

    private static Joint copyJoint(Joint bipedJoint, @Nullable OpenMatrix4f parentWorld, Map<String, OpenMatrix4f> ourWorld, Map<String, Joint> jointMap) {
        OpenMatrix4f world = ourWorld.get(bipedJoint.getName());
        OpenMatrix4f local;

        if (parentWorld == null) {
            local = new OpenMatrix4f(world);
        } else {
            OpenMatrix4f inverseParent = OpenMatrix4f.invert(parentWorld, null);
            local = OpenMatrix4f.mul(inverseParent, world, null);
        }

        Joint joint = new Joint(bipedJoint.getName(), bipedJoint.getId(), local);
        jointMap.put(joint.getName(), joint);

        for (Joint child : bipedJoint.getSubJoints()) {
            joint.addSubJoints(copyJoint(child, world, ourWorld, jointMap));
        }

        return joint;
    }

    /* ---------------------------------------------------------------------
     * Part definition
     * ------------------------------------------------------------------- */

    private record SimplePartDefinition(String partName) implements MeshPartDefinition {
        @Override
        public Mesh.RenderProperties renderProperties() {
            return null;
        }

        @Override
        public java.util.function.Supplier<OpenMatrix4f> getModelPartAnimationProvider() {
            return () -> null;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MeshPartDefinition definition && this.partName.equals(definition.partName());
        }

        @Override
        public int hashCode() {
            return this.partName.hashCode();
        }
    }
}
