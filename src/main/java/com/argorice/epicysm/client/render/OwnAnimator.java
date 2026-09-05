package com.argorice.epicysm.client.render;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import com.argorice.epicysm.client.convert.AnimatedBones;
import com.argorice.epicysm.client.convert.ConvertedModel;
import com.argorice.epicysm.client.convert.Molang;
import com.argorice.epicysm.client.convert.OwnAnimation;

/**
 * Plays a converted model's own everyday animation on the joints Epic
 * Fight does not drive. The body fights by Epic Fight; the tail, the
 * ears, the hair keep doing what the model's author keyframed for
 * standing, walking or swimming, as they would under Yes Steve Model.
 *
 * Runs after Epic Fight has posed the body and before the physics, so a
 * physics chain hanging from an animated bone follows it.
 */
public final class OwnAnimator {
    private static final OwnAnimator INSTANCE = new OwnAnimator();

    /** Seconds over which a clip fades into the next one. */
    private static final float BLEND_SECONDS = 0.25F;

    /** Blocks per second below which a player stands still. */
    private static final float STILL_SPEED = 0.15F;

    public static OwnAnimator get() {
        return INSTANCE;
    }

    /** One player's clip and where it is in it. */
    private static final class State {
        String modelId;
        @Nullable
        OwnAnimation.Clip clip;
        float clipStart;
        @Nullable
        OwnAnimation.Clip previous;
        float previousStart;
        float switchedAt;
        float lastX = Float.NaN;
        float lastZ;
        float lastTime = Float.NaN;
        float groundSpeed;
    }

    /** What the model's expressions may ask about the player this frame. */
    private static final class Queries implements Molang.Context {
        float animTime;
        float lifeTime;
        float groundSpeed;
        float verticalSpeed;
        float yawSpeed;
        float headPitch;
        float headYaw;
        float walkDistance;
        boolean sneaking;
        boolean sprinting;
        boolean onGround;
        boolean inWater;
        boolean swimming;

        @Override
        public float query(String name) {
            return switch (name) {
                case "anim_time" -> this.animTime;
                case "life_time", "time_stamp" -> this.lifeTime;
                case "ground_speed" -> this.groundSpeed;
                case "vertical_speed" -> this.verticalSpeed;
                case "yaw_speed" -> this.yawSpeed;
                case "head_x_rotation" -> this.headPitch;
                case "head_y_rotation" -> this.headYaw;
                case "modified_distance_moved", "walk_distance", "distance_from_camera" -> this.walkDistance;
                case "is_sneaking" -> this.sneaking ? 1.0F : 0.0F;
                case "is_sprinting" -> this.sprinting ? 1.0F : 0.0F;
                case "is_on_ground" -> this.onGround ? 1.0F : 0.0F;
                case "is_jumping" -> this.onGround ? 0.0F : 1.0F;
                case "is_in_water", "is_in_water_or_rain" -> this.inWater ? 1.0F : 0.0F;
                case "is_swimming" -> this.swimming ? 1.0F : 0.0F;
                case "is_alive" -> 1.0F;
                case "is_moving" -> this.groundSpeed > STILL_SPEED ? 1.0F : 0.0F;
                default -> 0.0F;
            };
        }
    }

    private final Map<UUID, State> states = new HashMap<>();
    private final Queries queries = new Queries();
    private final float[] rotation = new float[3];
    private final float[] position = new float[3];
    private final float[] scale = new float[3];
    private final float[] previousRotation = new float[3];
    private final float[] previousPosition = new float[3];
    private final float[] previousScale = new float[3];

    private OwnAnimator() {
    }

    public void reset() {
        this.states.clear();
    }

    public void forget(UUID player) {
        this.states.remove(player);
    }

    public void apply(AbstractClientPlayer entity, ConvertedModel model, Armature armature, float partialTicks) {
        List<AnimatedBones.Baked> joints = model.animatedJoints();
        OwnAnimation animation = model.animation();

        if (joints.isEmpty() || animation == null || animation.isEmpty()) {
            return;
        }

        State state = this.states.computeIfAbsent(entity.getUUID(), key -> new State());
        float time = (entity.tickCount + partialTicks) / 20.0F;

        if (!model.id().equals(state.modelId)) {
            state.modelId = model.id();
            state.clip = null;
            state.previous = null;
            state.lastX = Float.NaN;
            state.lastTime = Float.NaN;
        }

        this.measure(entity, state, time, partialTicks);
        OwnAnimation.Clip wanted = this.choose(entity, animation, state);

        if (wanted != state.clip) {
            state.previous = state.clip;
            state.previousStart = state.clipStart;
            state.clip = wanted;
            state.clipStart = time;
            state.switchedAt = time;
        }

        if (state.clip == null) {
            return;
        }

        float blend = state.previous == null ? 1.0F : Mth.clamp((time - state.switchedAt) / BLEND_SECONDS, 0.0F, 1.0F);

        if (blend >= 1.0F) {
            state.previous = null;
        }

        OpenMatrix4f[] poses = armature.getPoseMatrices();

        for (AnimatedBones.Baked def : joints) {
            Joint joint = armature.searchJointById(def.id());

            if (joint == null || def.parentId() >= poses.length || poses[def.parentId()] == null) {
                continue;
            }

            // The joint at rest under its posed parent; the animation is
            // applied in this frame.
            OpenMatrix4f base = OpenMatrix4f.mul(poses[def.parentId()], joint.getLocalTransform(), null);

            this.sample(state.clip, time - state.clipStart, def.boneName(), this.rotation, this.position, this.scale);

            if (state.previous != null && blend < 1.0F) {
                float[] r = this.previousRotation;
                float[] p = this.previousPosition;
                float[] s = this.previousScale;
                this.sample(state.previous, time - state.previousStart, def.boneName(), r, p, s);

                for (int i = 0; i < 3; i++) {
                    this.rotation[i] = r[i] + (this.rotation[i] - r[i]) * blend;
                    this.position[i] = p[i] + (this.position[i] - p[i]) * blend;
                    this.scale[i] = s[i] + (this.scale[i] - s[i]) * blend;
                }
            }

            poses[def.id()] = OpenMatrix4f.mul(base, this.transform(def), null);
        }
    }

    /** Speed and the like, from where the player was a moment ago. */
    private void measure(AbstractClientPlayer entity, State state, float time, float partialTicks) {
        float x = (float) Mth.lerp(partialTicks, entity.xo, entity.getX());
        float z = (float) Mth.lerp(partialTicks, entity.zo, entity.getZ());

        if (!Float.isNaN(state.lastTime) && time > state.lastTime) {
            float dt = time - state.lastTime;

            if (dt < 0.5F) {
                float dx = x - state.lastX;
                float dz = z - state.lastZ;
                float speed = (float) Math.sqrt(dx * dx + dz * dz) / dt;
                // Smoothed a little, so a stutter in the frame time does
                // not flick the clip back and forth.
                state.groundSpeed = state.groundSpeed + (speed - state.groundSpeed) * Math.min(1.0F, dt * 12.0F);
            }
        }

        state.lastX = x;
        state.lastZ = z;
        state.lastTime = time;

        Queries q = this.queries;
        q.lifeTime = time;
        q.groundSpeed = state.groundSpeed;
        q.verticalSpeed = (float) entity.getDeltaMovement().y * 20.0F;
        q.yawSpeed = Mth.wrapDegrees(entity.yBodyRot - entity.yBodyRotO) * 20.0F;
        q.headPitch = entity.getViewXRot(partialTicks);
        q.headYaw = Mth.wrapDegrees(Mth.lerp(partialTicks, entity.yHeadRotO, entity.yHeadRot)
                - Mth.lerp(partialTicks, entity.yBodyRotO, entity.yBodyRot));
        q.walkDistance = entity.walkDist;
        q.sneaking = entity.isCrouching();
        q.sprinting = entity.isSprinting();
        q.onGround = entity.onGround();
        q.inWater = entity.isInWater();
        q.swimming = entity.isSwimming();
    }

    /** The clip for what the player is doing, among the ones the model has. */
    @Nullable
    private OwnAnimation.Clip choose(AbstractClientPlayer entity, OwnAnimation animation, State state) {
        Queries q = this.queries;
        boolean moving = q.groundSpeed > STILL_SPEED;

        if (entity.isSleeping()) {
            return first(animation, "sleep", "idle");
        }

        if (entity.isFallFlying()) {
            return first(animation, "elytra_fly", "fly", "idle");
        }

        if (q.swimming) {
            return first(animation, "swim", "swim_stand", "idle");
        }

        if (q.inWater && !q.onGround) {
            return moving ? first(animation, "swim", "swim_stand", "idle") : first(animation, "swim_stand", "swim", "idle");
        }

        if (!q.onGround && !entity.onClimbable() && Math.abs(q.verticalSpeed) > 1.0F) {
            // A jump clip is often a one-shot; once it has run out it holds
            // its last frame, which is what a mid-air pose looks like anyway.
            return first(animation, "jump", "idle");
        }

        if (q.sneaking) {
            return moving ? first(animation, "sneaking", "sneak", "walk", "idle") : first(animation, "sneak", "idle");
        }

        if (moving) {
            return q.sprinting ? first(animation, "run", "walk", "idle") : first(animation, "walk", "idle");
        }

        return first(animation, "idle");
    }

    @Nullable
    private static OwnAnimation.Clip first(OwnAnimation animation, String... names) {
        for (String name : names) {
            OwnAnimation.Clip clip = animation.clip(name);

            if (clip != null) {
                return clip;
            }
        }

        return null;
    }

    /** The bone's three channels in the clip at the given time since the clip began. */
    private void sample(OwnAnimation.Clip clip, float sinceStart, String bone, float[] rotation, float[] position, float[] scale) {
        rotation[0] = 0.0F;
        rotation[1] = 0.0F;
        rotation[2] = 0.0F;
        position[0] = 0.0F;
        position[1] = 0.0F;
        position[2] = 0.0F;
        scale[0] = 1.0F;
        scale[1] = 1.0F;
        scale[2] = 1.0F;

        OwnAnimation.Track track = clip.bones().get(bone);

        if (track == null) {
            return;
        }

        float length = clip.length();
        float t = sinceStart < 0.0F ? 0.0F : sinceStart;

        if (clip.loop() && length > 0.0F) {
            t = t % length;
        } else if (t > length) {
            t = length;
        }

        this.queries.animTime = t;

        if (track.rotation() != null) {
            track.rotation().sample(t, this.queries, rotation);
        }

        if (track.position() != null) {
            track.position().sample(t, this.queries, position);
        }

        if (track.scale() != null) {
            track.scale().sample(t, this.queries, scale);
        }
    }

    /**
     * The bedrock bone transform in the joint's frame: the position offset
     * in the model's pixels, the rotation in degrees applied Z, then Y,
     * then X, and the scale. Bedrock's X points the other way from Epic
     * Fight's, which flips the X offset and the turns about Y and X - the
     * same reading the converter uses for the geometry.
     */
    private OpenMatrix4f transform(AnimatedBones.Baked def) {
        return boneTransform(this.rotation, this.position, this.scale, def.widthScale(), def.heightScale());
    }

    /** T(position) * Rz * Ry * Rx * S, as a column-major OpenMatrix4f. */
    static OpenMatrix4f boneTransform(float[] rotationDeg, float[] positionPx, float[] scale, float widthScale, float heightScale) {
        float a = -rotationDeg[0] * Mth.DEG_TO_RAD;
        float b = -rotationDeg[1] * Mth.DEG_TO_RAD;
        float c = rotationDeg[2] * Mth.DEG_TO_RAD;
        float sa = Mth.sin(a);
        float ca = Mth.cos(a);
        float sb = Mth.sin(b);
        float cb = Mth.cos(b);
        float sc = Mth.sin(c);
        float cc = Mth.cos(c);

        // Rows of R = Rz * Ry * Rx.
        float r00 = cc * cb;
        float r01 = cc * sb * sa - sc * ca;
        float r02 = cc * sb * ca + sc * sa;
        float r10 = sc * cb;
        float r11 = sc * sb * sa + cc * ca;
        float r12 = sc * sb * ca - cc * sa;
        float r20 = -sb;
        float r21 = cb * sa;
        float r22 = cb * ca;

        OpenMatrix4f m = new OpenMatrix4f();
        m.m00 = r00 * scale[0];
        m.m01 = r10 * scale[0];
        m.m02 = r20 * scale[0];
        m.m03 = 0.0F;
        m.m10 = r01 * scale[1];
        m.m11 = r11 * scale[1];
        m.m12 = r21 * scale[1];
        m.m13 = 0.0F;
        m.m20 = r02 * scale[2];
        m.m21 = r12 * scale[2];
        m.m22 = r22 * scale[2];
        m.m23 = 0.0F;
        m.m30 = -positionPx[0] / 16.0F * widthScale;
        m.m31 = positionPx[1] / 16.0F * heightScale;
        m.m32 = positionPx[2] / 16.0F * widthScale;
        m.m33 = 1.0F;
        return m;
    }
}
