package com.argorice.epicysm.client.render;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
    private static final float MAX_DT = 0.05F;         // clamp for lag spikes, seconds

    private static final class JointState {
        final Vector3f tip = new Vector3f();
        final Vector3f velocity = new Vector3f();
        final Vector3f previousTarget = new Vector3f();
        boolean initialized;
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

                Vector3f acceleration = new Vector3f(targetTip).sub(js.tip).mul(STIFFNESS);
                acceleration.y -= GRAVITY;
                Vector3f relativeVelocity = new Vector3f(js.velocity).sub(targetVelocity);
                acceleration.sub(relativeVelocity.mul(DAMPING));
                js.velocity.add(acceleration.mul(dt));
                js.tip.add(new Vector3f(js.velocity).mul(dt));
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
            float maxAngle = def.parentId() < PhysicsChains.FIRST_PHYSICS_JOINT_ID ? MAX_ANGLE_ROOT : MAX_ANGLE;

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
