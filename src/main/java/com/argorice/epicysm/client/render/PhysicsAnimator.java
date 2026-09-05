package com.argorice.epicysm.client.render;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.joml.Vector3f;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import com.argorice.epicysm.client.convert.ConvertedModel;
import com.argorice.epicysm.client.convert.PhysicsChains;

/**
 * Secondary-motion simulation for the physics joints of a converted model:
 * hair, tails, skirts and capes swing after the body instead of being glued
 * to it.
 */
public final class PhysicsAnimator {
    private static final PhysicsAnimator INSTANCE = new PhysicsAnimator();

    public static PhysicsAnimator get() {
        return INSTANCE;
    }

    private static final float STIFFNESS = 220.0F;   // spring toward the animated pose, 1/s^2
    private static final float DAMPING = 24.0F;      // relative-velocity decay, 1/s
    private static final float GRAVITY = 8.0F;       // extra droop while swinging, blocks/s^2
    private static final float MAX_ANGLE = 1.05F;      // radians a chain segment may bend
    private static final float MAX_ANGLE_ROOT = 0.35F; // chain roots hold the whole hairdo/skirt: stay firm
    private static final float MAX_ANGLE_LEGS = 1.5F;  // a skirt panel pushed by a knee may swing right up
    private static final float MAX_DT = 0.05F;         // clamp for lag spikes, seconds
    private static final float MAX_TARGET_SPEED = 25.0F; // the target's own speed, as far as the damping follows it, blocks/s
    private static final float MAX_STEP = 0.35F;       // how far a tip may move in one frame, in segment lengths

    /** The legs, as far as a skirt is concerned: a thigh this thick, plus this much cloth. */
    private static final float LEG_RADIUS = 0.11F;
    private static final float CLOTH_GAP = 0.03F;

    /** Points along a hanging piece, from the pivot, that are kept off the legs. */
    private static final float[] SAMPLES = { 0.25F, 0.5F, 0.75F, 1.0F };

    // Epic Fight's biped, by joint id.
    private static final int ROOT = 0;
    private static final int THIGH_R = 1;
    private static final int LEG_R = 2;
    private static final int THIGH_L = 4;
    private static final int LEG_L = 5;
    private static final int TORSO = 7;

    private static final class JointState {
        final Vector3f tip = new Vector3f();
        final Vector3f velocity = new Vector3f();
        final Vector3f previousTarget = new Vector3f();
        boolean initialized;
        /** How far from the legs each sample point hangs at rest: closer than this is a leg coming through. */
        @Nullable
        float[] restClearance;
    }

    private static final class EntityState {
        String modelId;
        float lastTime = Float.NaN;
        final Map<Integer, JointState> joints = new HashMap<>();
    }

    private final Map<UUID, EntityState> states = new HashMap<>();

    private PhysicsAnimator() {
    }

    public void reset() {
        this.states.clear();
    }

    public void apply(AbstractClientPlayer entity, ConvertedModel model, Armature armature, float partialTicks) {
        if (model.physicsJoints().isEmpty()) {
            return;
        }

        EntityState state = this.states.computeIfAbsent(entity.getUUID(), key -> new EntityState());

        if (!model.id().equals(state.modelId)) {
            state.modelId = model.id();
            state.joints.clear();
            state.lastTime = Float.NaN;
        }

        float time = entity.tickCount + partialTicks;
        float dt = Float.isNaN(state.lastTime) ? 0.0F : (time - state.lastTime) / 20.0F;
        state.lastTime = time;

        if (dt < 0.0F) {
            dt = 0.0F;
        } else if (dt > MAX_DT) {
            dt = MAX_DT;
        }

        // Model space -> world: the same yaw the renderer applies afterwards.
        float yaw = Mth.lerp(partialTicks, entity.yBodyRotO, entity.yBodyRot);
        float yawRad = (float) Math.toRadians(180.0F - yaw);
        float sin = Mth.sin(yawRad);
        float cos = Mth.cos(yawRad);
        Vec3 origin = entity.getPosition(partialTicks);

        OpenMatrix4f[] poses = armature.getPoseMatrices();
        Vector3f[][] legs = this.legs(poses, armature, sin, cos, origin);

        for (PhysicsChains.Baked def : model.physicsJoints()) {
            var joint = armature.searchJointById(def.id());

            if (joint == null || def.parentId() >= poses.length || poses[def.parentId()] == null) {
                continue;
            }

            // Re-derive the un-simulated pose from the (possibly simulated)
            // parent, so chains stack correctly.
            OpenMatrix4f base = OpenMatrix4f.mul(poses[def.parentId()], joint.getLocalTransform(), null);
            poses[def.id()] = base;

            Vector3f pivotModel = translation(base);
            Vector3f restModel = transformDirection(base, def.restLocal());
            float length = restModel.length();

            if (length < 1.0E-4F) {
                continue;
            }

            // To world space.
            Vector3f pivotWorld = rotateY(pivotModel, sin, cos).add((float) origin.x, (float) origin.y, (float) origin.z);
            Vector3f targetTip = rotateY(restModel, sin, cos).add(pivotWorld);

            JointState js = state.joints.computeIfAbsent(def.id(), key -> new JointState());

            if (!js.initialized) {
                js.tip.set(targetTip);
                js.velocity.set(0.0F, 0.0F, 0.0F);
                js.previousTarget.set(targetTip);
                js.initialized = true;

                if (def.aroundLegs()) {
                    js.restClearance = this.restClearance(joint, def, armature);
                }

                continue;
            }

            if (dt > 0.0F) {
                // Damped spring toward the animated target. Damping acts on
                // the velocity RELATIVE to the target, so steady movement
                Vector3f targetVelocity = new Vector3f(targetTip).sub(js.previousTarget).div(dt);
                js.previousTarget.set(targetTip);

                // A teleport or dimension change: snap instead of a slingshot.
                if (targetVelocity.length() > 60.0F || js.tip.distance(targetTip) > 3.0F) {
                    js.tip.set(targetTip);
                    js.velocity.set(0.0F, 0.0F, 0.0F);
                    poses[def.id()] = base;
                    continue;
                }

                // A swing Epic Fight plays moves the body far in a frame or
                // two; the damping follows the target's speed only so far,
                // or the tip is slung after it.
                if (targetVelocity.length() > MAX_TARGET_SPEED) {
                    targetVelocity.normalize(MAX_TARGET_SPEED);
                }

                Vector3f acceleration = new Vector3f(targetTip).sub(js.tip).mul(STIFFNESS);
                acceleration.y -= GRAVITY;
                Vector3f relativeVelocity = new Vector3f(js.velocity).sub(targetVelocity);
                acceleration.sub(relativeVelocity.mul(DAMPING));
                js.velocity.add(acceleration.mul(dt));

                // And however fast, a tip moves only so far in one frame.
                Vector3f step = new Vector3f(js.velocity).mul(dt);
                float most = length * MAX_STEP;

                if (step.length() > most) {
                    step.normalize(most);
                    js.velocity.set(step).div(dt);
                }

                js.tip.add(step);
            }

            // A skirt, a coat, a tail: whatever hangs from the hips or the
            // waist is kept off the legs, so a knee coming up does not come
            // out through the cloth.
            if (def.aroundLegs() && legs != null) {
                this.keepOffLegs(js, pivotWorld, legs);
            }

            // Keep the segment length: the tip slides on a sphere.
            Vector3f dirWorld = new Vector3f(js.tip).sub(pivotWorld);

            if (dirWorld.lengthSquared() < 1.0E-8F) {
                dirWorld.set(0.0F, -1.0F, 0.0F);
            }

            dirWorld.normalize();
            js.tip.set(pivotWorld).add(new Vector3f(dirWorld).mul(length));

            // Back to model space; rotate the joint from its animated rest
            // direction toward the simulated one.
            Vector3f dirModel = rotateY(dirWorld, -sin, cos);
            Vector3f restDir = new Vector3f(restModel).div(length);

            Vector3f axis = new Vector3f(restDir).cross(dirModel);
            float sinAngle = axis.length();
            float cosAngle = restDir.dot(dirModel);

            if (sinAngle < 1.0E-4F) {
                continue;
            }

            axis.div(sinAngle);
            float angle = (float) Math.atan2(sinAngle, cosAngle);

            // A chain root carries everything attached to it (the whole
            // scalp of hair, the full skirt): a hard swing there reads as
            // the piece detaching, so roots bend far less than the tips.
            float maxAngle = def.aroundLegs() && def.chainRoot() ? MAX_ANGLE_LEGS : def.chainRoot() ? MAX_ANGLE_ROOT : MAX_ANGLE;

            if (angle > maxAngle) {
                angle = maxAngle;

                // Keep the stored tip on the clamped cone so the simulation
                // does not wind up beyond what is actually shown.
                float c = Mth.cos(angle);
                float s = Mth.sin(angle);
                Vector3f clamped = new Vector3f(restDir).mul(c)
                        .add(new Vector3f(axis).cross(restDir).mul(s))
                        .add(new Vector3f(axis).mul(axis.dot(restDir) * (1.0F - c)));
                js.tip.set(pivotWorld).add(rotateY(clamped, sin, cos).mul(length));
            }

            OpenMatrix4f rotator = axisAngleAround(axis, angle, pivotModel);
            poses[def.id()] = OpenMatrix4f.mul(rotator, base, null);
        }
    }

    /**
     * The two legs this frame, in world space: hip, knee and ankle of
     * each. A model rarely has a knee joint of its own (one bone per leg is
     * the rule), so the leg is taken as the thigh joint's own down
     * direction, as long as the hip stood high at rest - the foot is on
     * the ground - bent at the knee joint's direction where there is one.
     */
    @Nullable
    private Vector3f[][] legs(OpenMatrix4f[] poses, Armature armature, float sin, float cos, Vec3 origin) {
        Vector3f[][] out = new Vector3f[2][];

        for (int side = 0; side < 2; side++) {
            int thighId = side == 0 ? THIGH_R : THIGH_L;
            int legId = side == 0 ? LEG_R : LEG_L;

            if (thighId >= poses.length || poses[thighId] == null) {
                return null;
            }

            var thigh = armature.searchJointById(thighId);

            if (thigh == null) {
                return null;
            }

            float legLength = OpenMatrix4f.invert(thigh.getToOrigin(), null).m31;

            if (!(legLength > 0.05F)) {
                return null;
            }

            Vector3f hipModel = translation(poses[thighId]);
            Vector3f thighDown = transformDirection(poses[thighId], new Vector3f(0.0F, -1.0F, 0.0F));
            Vector3f shinDown = legId < poses.length && poses[legId] != null
                    ? transformDirection(poses[legId], new Vector3f(0.0F, -1.0F, 0.0F)) : thighDown;

            if (thighDown.lengthSquared() < 1.0E-6F || shinDown.lengthSquared() < 1.0E-6F) {
                return null;
            }

            thighDown.normalize();
            shinDown.normalize();
            Vector3f kneeModel = new Vector3f(hipModel).add(new Vector3f(thighDown).mul(legLength * 0.5F));
            Vector3f ankleModel = new Vector3f(kneeModel).add(new Vector3f(shinDown).mul(legLength * 0.5F));
            Vector3f at = new Vector3f((float) origin.x, (float) origin.y, (float) origin.z);
            out[side] = new Vector3f[] {
                    rotateY(hipModel, sin, cos).add(at),
                    rotateY(kneeModel, sin, cos).add(at),
                    rotateY(ankleModel, sin, cos).add(at) };
        }

        return out;
    }

    /**
     * How far each sample point of a hanging piece is from the legs when
     * everything stands at rest, in model space. A dress hangs right next
     * to the legs; that is not a leg coming through, so only coming closer
     * than this counts.
     */
    @Nullable
    private float[] restClearance(yesman.epicfight.api.animation.Joint joint, PhysicsChains.Baked def, Armature armature) {
        try {
            OpenMatrix4f bind = OpenMatrix4f.invert(joint.getToOrigin(), null);
            Vector3f pivot = translation(bind);
            Vector3f tip = new Vector3f(pivot).add(def.restLocal());
            float[] out = new float[SAMPLES.length];
            java.util.Arrays.fill(out, Float.MAX_VALUE);

            for (int side = 0; side < 2; side++) {
                var thigh = armature.searchJointById(side == 0 ? THIGH_R : THIGH_L);

                if (thigh == null) {
                    return null;
                }

                Vector3f hip = translation(OpenMatrix4f.invert(thigh.getToOrigin(), null));
                Vector3f ankle = new Vector3f(hip.x, 0.0F, hip.z);

                for (int k = 0; k < SAMPLES.length; k++) {
                    Vector3f point = new Vector3f(pivot).lerp(tip, SAMPLES[k]);
                    out[k] = Math.min(out[k], clearance(point, hip, ankle));
                }
            }

            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Distance from a point to a segment. */
    private static float clearance(Vector3f point, Vector3f from, Vector3f to) {
        Vector3f along = new Vector3f(to).sub(from);
        float span = along.lengthSquared();

        if (span < 1.0E-6F) {
            return point.distance(from);
        }

        float at = Mth.clamp(new Vector3f(point).sub(from).dot(along) / span, 0.0F, 1.0F);
        return point.distance(new Vector3f(from).add(along.mul(at)));
    }

    /**
     * Keeps a hanging piece off the legs. The piece runs from its pivot
     * to its tip; a few points along it are tested against each leg,
     * thigh and shin as capsules of the thigh's thickness plus a little
     * cloth - but never further than the point hangs from the legs at
     * rest, or a dress would be thrown off the legs it is meant to cover.
     * A point that has come too close is pushed straight out, and since
     * the piece turns about its pivot, the tip goes that much further: a
     * knee coming up half-way along a skirt panel lifts the whole panel.
     * The worst point decides, twice over, so one push does not undo another.
     */
    private void keepOffLegs(JointState js, Vector3f pivotWorld, Vector3f[][] legs) {
        float[] rest = js.restClearance;

        for (int pass = 0; pass < 2; pass++) {
            Vector3f worst = null;
            float worstLength = 0.0F;

            for (int k = 0; k < SAMPLES.length; k++) {
                float at = SAMPLES[k];
                float keep = LEG_RADIUS + CLOTH_GAP;

                if (rest != null && rest[k] < Float.MAX_VALUE) {
                    keep = Math.min(keep, rest[k] * 0.9F);
                }

                if (keep <= 0.0F) {
                    continue;
                }

                Vector3f point = new Vector3f(pivotWorld).lerp(js.tip, at);

                for (Vector3f[] leg : legs) {
                    Vector3f push = this.pushOut(point, leg[0], leg[1], keep, pivotWorld);

                    if (push == null) {
                        push = this.pushOut(point, leg[1], leg[2], keep, pivotWorld);
                    }

                    if (push == null) {
                        continue;
                    }

                    Vector3f atTip = push.mul(Math.min(1.0F / at, 3.0F));

                    if (atTip.length() > worstLength) {
                        worstLength = atTip.length();
                        worst = atTip;
                    }
                }
            }

            if (worst == null) {
                return;
            }

            js.tip.add(worst);
            Vector3f out = new Vector3f(worst).normalize();
            float into = js.velocity.dot(out);

            if (into < 0.0F) {
                js.velocity.sub(out.mul(into));
            }
        }
    }

    /** How far a point inside a capsule has to move to be out of it, or null when it is out already. */
    @Nullable
    private Vector3f pushOut(Vector3f point, Vector3f from, Vector3f to, float keep, Vector3f pivotWorld) {
        Vector3f along = new Vector3f(to).sub(from);
        float span = along.lengthSquared();

        if (span < 1.0E-6F) {
            return null;
        }

        float at = Mth.clamp(new Vector3f(point).sub(from).dot(along) / span, 0.0F, 1.0F);
        Vector3f nearest = new Vector3f(from).add(along.mul(at));
        Vector3f out = new Vector3f(point).sub(nearest);
        float away = out.length();

        if (away >= keep) {
            return null;
        }

        if (away < 1.0E-4F) {
            // Right on the bone: away from the hips, sideways.
            out.set(point).sub(pivotWorld);
            out.y = 0.0F;

            if (out.lengthSquared() < 1.0E-6F) {
                out.set(1.0F, 0.0F, 0.0F);
            }

            out.normalize();
        } else {
            out.div(away);
        }

        return out.mul(keep - away);
    }

    /* ---------------------------------------------------------------------
     * Small matrix helpers (OpenMatrix4f is column-major: m30..m32 hold the
     * translation).
     * ------------------------------------------------------------------- */

    private static Vector3f translation(OpenMatrix4f m) {
        return new Vector3f(m.m30, m.m31, m.m32);
    }

    private static Vector3f transformDirection(OpenMatrix4f m, Vector3f v) {
        return new Vector3f(
                m.m00 * v.x + m.m10 * v.y + m.m20 * v.z,
                m.m01 * v.x + m.m11 * v.y + m.m21 * v.z,
                m.m02 * v.x + m.m12 * v.y + m.m22 * v.z);
    }

    private static Vector3f rotateY(Vector3f v, float sin, float cos) {
        return new Vector3f(cos * v.x + sin * v.z, v.y, -sin * v.x + cos * v.z);
    }

    /** Rotation of `angle` around `axis`, about the point `center`. */
    private static OpenMatrix4f axisAngleAround(Vector3f axis, float angle, Vector3f center) {
        float c = Mth.cos(angle);
        float s = Mth.sin(angle);
        float t = 1.0F - c;
        float x = axis.x;
        float y = axis.y;
        float z = axis.z;

        OpenMatrix4f m = new OpenMatrix4f();
        m.m00 = t * x * x + c;
        m.m01 = t * x * y + s * z;
        m.m02 = t * x * z - s * y;
        m.m10 = t * x * y - s * z;
        m.m11 = t * y * y + c;
        m.m12 = t * y * z + s * x;
        m.m20 = t * x * z + s * y;
        m.m21 = t * y * z - s * x;
        m.m22 = t * z * z + c;

        // T(center) * R * T(-center): translation = center - R*center.
        m.m30 = center.x - (m.m00 * center.x + m.m10 * center.y + m.m20 * center.z);
        m.m31 = center.y - (m.m01 * center.x + m.m11 * center.y + m.m21 * center.z);
        m.m32 = center.z - (m.m02 * center.x + m.m12 * center.y + m.m22 * center.z);
        m.m33 = 1.0F;
        return m;
    }
}
