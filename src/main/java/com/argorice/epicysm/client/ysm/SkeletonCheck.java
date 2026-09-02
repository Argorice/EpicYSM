package com.argorice.epicysm.client.ysm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;

import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import com.argorice.epicysm.EpicYsm;
import com.argorice.epicysm.client.ModelManager;
import com.argorice.epicysm.client.convert.JointMapper;
import com.argorice.epicysm.client.convert.ModelConverter;
import com.argorice.epicysm.client.model.BedrockGeometry;
import com.argorice.epicysm.client.model.BedrockGeometryParser;
import com.argorice.epicysm.client.model.YsmModelSource;

/** Asks the game whether the two halves of this mod agree with each other. */
public final class SkeletonCheck {
    private SkeletonCheck() {
    }

    /** What the check found, ready to be read out or pasted into a chat. */
    public record Report(String model, float worst, String where, float head, float mirrored, int joints) {
        public String line() {
            return String.format(Locale.ROOT,
                    "Skeleton check on %s: the skeleton this mod builds for an unreadable model differs from the"
                            + " converted one by %.4f blocks at worst (%s), over %d joint(s), on a model %.3f blocks"
                            + " to the head. Read the other way round it would be %.4f.",
                    this.model, this.worst, this.where, this.joints, this.head, this.mirrored);
        }
    }

    /**
     * @param id a model this mod can read, so that both halves can be run on it
     */
    public static Report run(String id) throws Exception {
        Map<String, YsmModelSource> sources = ModelManager.get().sources();
        YsmModelSource source = sources.get(id);

        if (source == null) {
            throw new IllegalArgumentException("no readable model called '" + id + "'");
        }

        // Half one: the converter, exactly as it runs for a readable model.
        ModelConverter.Result converted = ModelConverter.convert(id, source);
        Map<String, Vector3f> theirs = new LinkedHashMap<>();
        collect(converted.armature().rootJoint, new OpenMatrix4f(), theirs);

        // Half two: the solver, on the same bones, straight out of the file.
        BedrockGeometry geometry = BedrockGeometryParser.parse(source.readMainModel());
        float best = Float.MAX_VALUE;
        float other = Float.MAX_VALUE;
        String where = "";
        int counted = 0;
        float head = 0.0F;

        for (boolean flip : new boolean[] { true, false }) {
            Map<String, Vector3f> ours = solve(geometry, flip);
            Vector3f ourHead = ours.get("Head");
            Vector3f theirHead = theirs.get("Head");

            if (ourHead == null || theirHead == null || ourHead.y < 0.001F || theirHead.y < 0.001F) {
                continue;
            }

            // Both skeletons are measured in their own units - the converter
            // in blocks, the solver in whatever Yes Steve Model holds. Each
            float worst = 0.0F;
            String worstJoint = "";
            int joints = 0;

            for (String joint : JointMapper.JOINT_NAMES) {
                Vector3f mine = ours.get(joint);
                Vector3f yours = theirs.get(joint);

                if (mine == null || yours == null) {
                    continue;
                }

                joints++;
                float dx = mine.x / ourHead.y - yours.x / theirHead.y;
                float dy = mine.y / ourHead.y - yours.y / theirHead.y;
                float dz = mine.z / ourHead.y - yours.z / theirHead.y;
                float off = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * theirHead.y;

                if (off > worst) {
                    worst = off;
                    worstJoint = joint;
                }
            }

            if (flip) {
                best = worst;
                where = worstJoint;
                counted = joints;
                head = theirHead.y;
            } else {
                other = worst;
            }
        }

        if (best == Float.MAX_VALUE) {
            throw new IllegalStateException("could not build a skeleton for '" + id + "'");
        }

        Report report = new Report(id, best, where.isEmpty() ? "-" : where, head, other, counted);
        com.argorice.epicysm.client.Diag.info(report.line());
        return report;
    }

    /** Every joint's resting place in the converted armature, in blocks. */
    private static void collect(Joint joint, OpenMatrix4f parent, Map<String, Vector3f> out) {
        OpenMatrix4f world = OpenMatrix4f.mul(parent, joint.getLocalTransform(), null);
        out.put(joint.getName(), new Vector3f(world.m30, world.m31, world.m32));

        for (Joint child : joint.getSubJoints()) {
            collect(child, world, out);
        }
    }

    /**
     * The solver's resting skeleton, built from the model file the way Yes
     * Steve Model would hand it over.
     *
     * @param flip whether the sideways axis is turned round on the way in,
     *             which is how the live pivots in the log read
     */
    private static Map<String, Vector3f> solve(BedrockGeometry geometry, boolean flip) {
        List<YsmLiveSkeleton.LiveBone> bones = new ArrayList<>();
        Map<String, float[]> rests = new HashMap<>();

        for (BedrockGeometry.Bone bone : geometry.bones()) {
            float[] pivot = bone.pivot() == null ? new float[3] : bone.pivot();
            float[] rotation = bone.rotation() == null ? new float[3] : bone.rotation();
            bones.add(new YsmLiveSkeleton.LiveBone(bone.name(), flip ? -pivot[0] : pivot[0], pivot[1], pivot[2],
                    bone.parent()));
            rests.put(bone.name(), new float[] { (float) Math.toRadians(rotation[0]),
                    (float) Math.toRadians(rotation[1]), (float) Math.toRadians(rotation[2]) });
        }

        YsmPoseSolver solver = new YsmPoseSolver(
                new YsmLiveSkeleton.Skeleton(bones, List.of(), new Object()), rests);
        return solver.isReady() ? solver.bindPlaces() : Map.of();
    }
}
