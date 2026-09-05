package com.argorice.epicysm.client.ysm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.gameasset.Armatures;

import com.argorice.epicysm.EpicYsm;

/**
 * Works out where every bone of a model should be, by building the same
 * skeleton the converted models are built from and letting Epic Fight pose
 * it.
 */
public final class YsmPoseSolver {
    /** Where one bone must end up: its own turn, and how far it must move. */
    public record Placement(Quaternionf rotation, Vector3f offset) {
    }

    /**
     * Which of the model's bones gives each Epic Fight joint its place, in
     * the order the converted models try them. Deliberately identical: the
     * whole point is to build the same skeleton.
     */
    private static final Map<String, String[]> PIVOT_SOURCES = new LinkedHashMap<>();

    static {
        PIVOT_SOURCES.put("Root", new String[] { "AllBody", "MAllBody", "UpBody" });
        PIVOT_SOURCES.put("Torso", new String[] { "UpBody", "AllBody", "DownBody" });
        PIVOT_SOURCES.put("Chest", new String[] { "UpperBody", "UpBody" });
        PIVOT_SOURCES.put("Head", new String[] { "Head", "AllHead" });
        PIVOT_SOURCES.put("Shoulder_R", new String[] { "RightArm" });
        PIVOT_SOURCES.put("Arm_R", new String[] { "RightArm" });
        PIVOT_SOURCES.put("Hand_R", new String[] { "RightForeArm", "RightArm" });
        PIVOT_SOURCES.put("Elbow_R", new String[] { "RightForeArm", "RightArm" });
        PIVOT_SOURCES.put("Tool_R", new String[] { "RightHandLocator", "RightHand", "RightForeArm" });
        PIVOT_SOURCES.put("Shoulder_L", new String[] { "LeftArm" });
        PIVOT_SOURCES.put("Arm_L", new String[] { "LeftArm" });
        PIVOT_SOURCES.put("Hand_L", new String[] { "LeftForeArm", "LeftArm" });
        PIVOT_SOURCES.put("Elbow_L", new String[] { "LeftForeArm", "LeftArm" });
        PIVOT_SOURCES.put("Tool_L", new String[] { "LeftHandLocator", "LeftHand", "LeftForeArm" });
        PIVOT_SOURCES.put("Thigh_R", new String[] { "RightLeg" });
        PIVOT_SOURCES.put("Leg_R", new String[] { "RightLowerLeg", "RightLeg" });
        PIVOT_SOURCES.put("Knee_R", new String[] { "RightLowerLeg", "RightLeg" });
        PIVOT_SOURCES.put("Thigh_L", new String[] { "LeftLeg" });
        PIVOT_SOURCES.put("Leg_L", new String[] { "LeftLowerLeg", "LeftLeg" });
        PIVOT_SOURCES.put("Knee_L", new String[] { "LeftLowerLeg", "LeftLeg" });
    }

    /** Which Epic Fight joint a bone of a given name follows. */
    private static final Map<String, String> BONE_TO_JOINT = new LinkedHashMap<>();

    static {
        BONE_TO_JOINT.put("head", "Head");
        BONE_TO_JOINT.put("allhead", "Head");
        BONE_TO_JOINT.put("hair", "Head");
        BONE_TO_JOINT.put("face", "Head");
        BONE_TO_JOINT.put("upperbody", "Chest");
        BONE_TO_JOINT.put("mupperbody", "Chest");
        BONE_TO_JOINT.put("breast", "Chest");
        BONE_TO_JOINT.put("collar", "Chest");
        BONE_TO_JOINT.put("arm", "Chest");
        BONE_TO_JOINT.put("upbody", "Torso");
        BONE_TO_JOINT.put("allbody", "Torso");
        BONE_TO_JOINT.put("mallbody", "Torso");
        BONE_TO_JOINT.put("downbody", "Torso");
        BONE_TO_JOINT.put("body", "Torso");
        BONE_TO_JOINT.put("skirt", "Torso");
        BONE_TO_JOINT.put("waist", "Torso");
        BONE_TO_JOINT.put("leg", "Torso");
        BONE_TO_JOINT.put("leftarm", "Arm_L");
        BONE_TO_JOINT.put("leftforearm", "Hand_L");
        BONE_TO_JOINT.put("lefthand", "Hand_L");
        BONE_TO_JOINT.put("rightarm", "Arm_R");
        BONE_TO_JOINT.put("rightforearm", "Hand_R");
        BONE_TO_JOINT.put("righthand", "Hand_R");
        BONE_TO_JOINT.put("leftleg", "Thigh_L");
        BONE_TO_JOINT.put("leftlowerleg", "Leg_L");
        BONE_TO_JOINT.put("leftfoot", "Leg_L");
        BONE_TO_JOINT.put("rightleg", "Thigh_R");
        BONE_TO_JOINT.put("rightlowerleg", "Leg_R");
        BONE_TO_JOINT.put("rightfoot", "Leg_R");
    }

    /**
     * The joints a prop with no name worth reading may be hung on, in the
     * converted models' order.
     */
    private static final String[] ANY_JOINT = { "Root", "Torso", "Chest", "Head", "Arm_L", "Arm_R",
            "Hand_L", "Hand_R", "Thigh_L", "Thigh_R", "Leg_L", "Leg_R" };

    private static final String[] BODY_JOINTS = { "Root", "Torso", "Chest", "Head" };

    private final Map<String, YsmLiveSkeleton.LiveBone> byName = new HashMap<>();

    /** The model's bones, parents always before their children. */
    private final List<YsmLiveSkeleton.LiveBone> ordered = new ArrayList<>();

    private final Map<String, Matrix4f> jointBindLocal = new LinkedHashMap<>();
    private final Map<String, Matrix4f> jointBindWorld = new LinkedHashMap<>();
    private final Map<String, Matrix4f> restWorldByBone = new HashMap<>();

    /** Which joint carries each bone - the converted models' answer. */
    private final Map<String, String> boneJoint = new HashMap<>();

    /** The same, but only for bones that say what they are by name. */
    private final Map<String, String> namedJoint = new HashMap<>();

    /**
     * Whether bones that do not say what they are - hair, skirts, coats,
     * props - are driven as well, the way the converted models drive them:
     * by the nearest name above them, or failing that by whatever joint
     * they rest closest to.
     */
    private static boolean carryAll = true;

    public static boolean carryAll() {
        return carryAll;
    }

    public static void setCarryAll(boolean value) {
        carryAll = value;
    }

    /** Whether an animation's travel is carried over. */
    private static boolean travel = true;

    public static boolean travel() {
        return travel;
    }

    public static void setTravel(boolean value) {
        travel = value;
    }

    /** The joints this model gave a bone of its own, rather than borrowed. */
    private final java.util.Set<String> sourced = new java.util.HashSet<>();
    private final List<String> jointOrder = new ArrayList<>();
    private final Map<String, String> jointParent = new HashMap<>();
    private final Map<String, Joint> jointByName = new HashMap<>();

    private boolean ready;

    public boolean isReady() {
        return this.ready;
    }

    private final Map<String, float[]> restRotations = new HashMap<>();

    public YsmPoseSolver(YsmLiveSkeleton.Skeleton skeleton, Map<String, float[]> rests) {
        try {
            for (Map.Entry<String, float[]> entry : rests.entrySet()) {
                this.restRotations.put(key(entry.getKey()), entry.getValue());
            }

            this.build(skeleton);
        } catch (Throwable t) {
            EpicYsm.LOGGER.debug("Could not build a skeleton for the live model", t);
            this.ready = false;
        }
    }

    private void build(YsmLiveSkeleton.Skeleton skeleton) {
        for (YsmLiveSkeleton.LiveBone bone : skeleton.bones()) {
            this.byName.put(key(bone.name()), bone);
        }

        for (YsmLiveSkeleton.LiveBone bone : skeleton.bones()) {
            this.addInOrder(bone, 0);

            if (bone.parent() != null) {
                this.roots.add(key(bone.parent()));
            }
        }

        // Which way round this skeleton is. Yes Steve Model turns the
        // sideways axis round as it loads - the live pivots in the log have
        for (String[] pair : new String[][] { { "leftarm", "rightarm" }, { "leftleg", "rightleg" },
                { "lefthand", "righthand" }, { "leftfoot", "rightfoot" } }) {
            YsmLiveSkeleton.LiveBone left = this.byName.get(pair[0]);
            YsmLiveSkeleton.LiveBone right = this.byName.get(pair[1]);

            if (left != null && right != null && Math.abs(left.pivotX() - right.pivotX()) > 0.01F) {
                this.mirrorX = left.pivotX() > right.pivotX();
                break;
            }
        }

        Armature biped = Armatures.BIPED.get();
        this.collectJoints(biped.rootJoint, null);

        Map<String, Vector3f> places = new LinkedHashMap<>();
        Map<String, Vector3f> bipedPlaces = new LinkedHashMap<>();
        this.collectBipedPlaces(biped.rootJoint, new Matrix4f(), bipedPlaces);

        // How big this model is next to Epic Fight's own skeleton, measured
        // where every model has something to measure: the head. It is what
        // a joint with no bone of its own is brought down to.
        float scale = this.modelScale(bipedPlaces);
        this.bipedScale = scale;
        this.buildBiped(bipedPlaces, scale);

        for (String joint : this.jointOrder) {
            Vector3f found = this.rawPlaceOf(joint);

            if (found != null) {
                places.put(joint, found);
                this.sourced.add(joint);
                continue;
            }

            // A joint this model has no bone for is not left out: it goes
            // where Epic Fight rests it, brought down to this model's size.
            Vector3f want = bipedPlaces.get(joint);

            if (want != null) {
                places.put(joint, new Vector3f(want).mul(scale));
            }
        }

        this.repair(places, bipedPlaces, scale);

        // The hips, the waist and the shoulders are worked out last, from
        // joints that have by now been repaired - the same order the
        // converted models use.
        this.placeBody(places);

        Map<String, Matrix4f> bindWorld = new LinkedHashMap<>();

        for (String joint : this.jointOrder) {
            Vector3f place = places.get(joint);

            if (place == null) {
                place = this.fallbackPlace(joint, bindWorld);
            }

            if (place == null) {
                return;
            }

            String parent = this.jointParent.get(joint);
            Matrix4f parentWorld = parent == null ? new Matrix4f() : bindWorld.get(parent);

            // The orientation a joint rests at is the one it ends up with
            // after every joint above it, not the turn it adds on its own.
            Quaternionf restWorld = parentWorld.getNormalizedRotation(new Quaternionf())
                    .mul(this.bindRotation(joint));
            Matrix4f world = new Matrix4f().translation(place).rotate(restWorld);
            bindWorld.put(joint, world);
            this.jointBindWorld.put(joint, new Matrix4f(world));
            this.jointBindLocal.put(joint, new Matrix4f(parentWorld).invert().mul(world));
        }

        // Where every bone of the model rests, whole - not just the spot its
        // pivot rests at. A bone is carried by its joint rather than dropped
        // onto it, so what it was carrying to begin with has to be known.
        for (YsmLiveSkeleton.LiveBone bone : this.ordered) {
            this.restWorldByBone.put(key(bone.name()), this.restWorld(bone));
        }

        this.findCarriers();
        this.countSubtrees();

        for (YsmLiveSkeleton.LiveBone bone : this.ordered) {
            String joint = this.jointFor(bone, places);

            if (joint != null) {
                this.boneJoint.put(key(bone.name()), joint);
            }

            String own = BONE_TO_JOINT.get(key(bone.name()));

            if (own != null) {
                this.namedJoint.put(key(bone.name()), own);
            }
        }

        this.bindPlaces.putAll(places);
        this.ready = this.jointBindLocal.size() == this.jointOrder.size() && !this.ordered.isEmpty();
    }

    /* ------------------------------------------------------------------
     * Epic Fight's own skeleton, at this model's size, in the same frame -
     * so that where the animation puts one hand against the other can be
     * read off it and given to this model's hands.
     * ------------------------------------------------------------------ */

    /** How many of this model's units one of Epic Fight's blocks is, for the biped built beside it. */
    private float bipedScale = 1.0F;

    /** The biped's own joints at rest, each in its parent's frame, at this model's size. */
    private final Map<String, Matrix4f> bipedBindLocal = new LinkedHashMap<>();

    private void buildBiped(Map<String, Vector3f> bipedPlaces, float scale) {
        Map<String, Matrix4f> world = new LinkedHashMap<>();

        for (String joint : this.jointOrder) {
            Vector3f place = bipedPlaces.get(joint);

            if (place == null) {
                this.bipedBindLocal.clear();
                return;
            }

            String parent = this.jointParent.get(joint);
            Matrix4f parentWorld = parent == null ? new Matrix4f() : world.get(parent);
            Quaternionf restWorld = parentWorld.getNormalizedRotation(new Quaternionf()).mul(this.bindRotation(joint));
            Matrix4f own = new Matrix4f().translation(new Vector3f(place).mul(scale)).rotate(restWorld);
            world.put(joint, own);
            this.bipedBindLocal.put(joint, new Matrix4f(parentWorld).invert().mul(own));
        }
    }

    /** The biped posed with this frame's animation, joint by joint, in this model's frame and units. */
    private Map<String, Matrix4f> poseBiped(Pose pose) {
        Map<String, Matrix4f> posed = new LinkedHashMap<>();

        if (this.bipedBindLocal.size() != this.jointOrder.size()) {
            return posed;
        }

        for (String joint : this.jointOrder) {
            String parent = this.jointParent.get(joint);
            Matrix4f parentWorld = parent == null ? new Matrix4f() : posed.get(parent);
            Matrix4f frame = new Matrix4f(parentWorld).mul(this.bipedBindLocal.get(joint));
            Vector3f shift = travel ? localTranslation(pose, joint) : new Vector3f();
            float ruler = unitsPerBlock();
            posed.put(joint, frame.translate(shift.x * ruler, shift.y * ruler, shift.z * ruler)
                    .rotate(localRotation(pose, joint)));
        }

        return posed;
    }

    /**
     * How much the left hand follows the right one, by how close the
     * biped's hands are: wholly within HANDS_NEAR blocks, not at all beyond
     * HANDS_FAR, in between in between. A single switch-over distance was
     * tried first, with a little hysteresis; a stance that holds the hands
     * right at that distance - a sheath at the hip beside a raised blade -
     * then switched the hand on and off every few frames.
     */
    private static final float HANDS_NEAR = 0.45F;
    private static final float HANDS_FAR = 0.6F;

    /** How fast the left hand moves between its own place and the meeting place, per second. */
    private static final float HANDS_RATE = 20.0F;

    private boolean handsTogether;
    private float handsWeight;
    private long handsStepped;
    private long handsTraced;

    /** How far the hands have been brought together this frame, 0 to 1. */
    public float handsWeight() {
        return this.handsWeight;
    }

    /** How the animation holds the left tool joint against the right, in the space the joints are drawn in; null when unknown. */
    @Nullable
    public Matrix4f handsRelationAsDrawn() {
        Matrix4f relation = this.handsRelation;
        return relation == null ? null : this.asDrawn(relation);
    }

    @Nullable
    private Matrix4f handsRelation;

    /**
     * Where the animation holds one hand against the other, given to this
     * model's arms. Epic Fight's animations are written for its own body:
     * when the two hands work on one thing - a blade being sheathed, a hilt
     * held in both hands - they stand at a fixed distance and angle from
     * each other, and on a body with other arms that distance comes out
     * wrong, so the blade misses the sheath. Here the left hand is brought
     * to where the biped's stands relative to this model's right hand, and
     * its upper arm and forearm are turned to reach - so that whatever it
     * holds meets what the right hand holds, and the hand is on it. Only
     * while the hands are close; the move in and out is eased over a
     * fraction of a second.
     */
    private void meetHands(Map<String, Matrix4f> posed, Pose pose) {
        Map<String, Matrix4f> biped = this.poseBiped(pose);
        Matrix4f bipedRight = biped.get("Tool_R");
        Matrix4f bipedLeft = biped.get("Tool_L");
        Matrix4f right = posed.get("Tool_R");
        Matrix4f left = posed.get("Tool_L");

        if (bipedRight == null || bipedLeft == null || right == null || left == null || this.bipedScale <= 0.0F) {
            this.handsRelation = null;
            return;
        }

        Matrix4f relation = new Matrix4f(bipedRight).invert().mul(bipedLeft);
        this.handsRelation = relation;

        float apart = bipedLeft.getTranslation(new Vector3f()).distance(bipedRight.getTranslation(new Vector3f()))
                / this.bipedScale;

        // A distance that is not a number - the body scaled to nothing -
        // would stick in the weight for good, and a hand posed by it is
        // drawn nowhere. It counts as the hands being apart.
        if (!Float.isFinite(apart)) {
            apart = HANDS_FAR;
        }

        if (!Float.isFinite(this.handsWeight)) {
            this.handsWeight = 0.0F;
        }

        this.handsTogether = apart < HANDS_NEAR;

        long now = System.nanoTime();
        float seconds = this.handsStepped == 0L ? 0.0F : Math.min(0.25F, (now - this.handsStepped) / 1.0e9F);
        this.handsStepped = now;
        float goal = apart <= HANDS_NEAR ? 1.0F : apart >= HANDS_FAR ? 0.0F : (HANDS_FAR - apart) / (HANDS_FAR - HANDS_NEAR);
        float step = HANDS_RATE * seconds;
        this.handsWeight = Math.max(0.0F, Math.min(1.0F, this.handsWeight + Math.max(-step, Math.min(step, goal - this.handsWeight))));

        if (this.handsWeight <= 0.0F) {
            return;
        }

        // The left tool joint as the animation holds it against the right,
        // hung off this model's right tool joint - the right hand, and the
        // blade in it, stay exactly where they are.
        Matrix4f meetLeft = new Matrix4f(right).mul(relation);
        Matrix4f wantLeft = between(left, meetLeft, this.handsWeight);
        float offLeft = this.reachArm(posed, "L", wantLeft);

        // Said once when the hands first meet, then now and then while
        // they are together - so that the log shows the left hand being
        // brought to the right one, and how far it fell short.
        long since = com.argorice.epicysm.client.Diag.on() ? 1_000_000_000L : 30_000_000_000L;

        if (!this.saidHands || now - this.handsTraced > since) {
            this.handsTraced = now;
            EpicYsm.LOGGER.info("Hands: {} the animation holds the hands {} blocks apart, so the left hand is brought"
                    + " onto what the right one holds (weight {}); it fell {} units short",
                    this.saidHands ? "still:" : "first time:", String.format(Locale.ROOT, "%.2f", apart),
                    String.format(Locale.ROOT, "%.2f", this.handsWeight), String.format(Locale.ROOT, "%.2f", offLeft));
            this.saidHands = true;
        }
    }

    private boolean saidHands;

    /** A frame part of the way from one to another: the place straight between, the turn the short way round. */
    private static Matrix4f between(Matrix4f from, Matrix4f to, float part) {
        Vector3f place = from.getTranslation(new Vector3f()).lerp(to.getTranslation(new Vector3f()), part);
        Quaternionf turn = from.getNormalizedRotation(new Quaternionf()).slerp(to.getNormalizedRotation(new Quaternionf()), part);
        return new Matrix4f().translation(place).rotate(turn);
    }

    /**
     * Two bones reaching for a frame: the upper arm from the shoulder to
     * the elbow, the forearm from the elbow to the hand; the hand then
     * faces the way asked. Returns how far short the hand fell, in units.
     */
    private float reachArm(Map<String, Matrix4f> posed, String side, Matrix4f want) {
        String armName = "Arm_" + side;
        String handName = "Hand_" + side;
        String toolName = "Tool_" + side;
        Matrix4f arm = posed.get(armName);
        Matrix4f hand = posed.get(handName);
        Matrix4f tool = posed.get(toolName);

        if (arm == null || hand == null || tool == null) {
            return Float.NaN;
        }

        Vector3f target = want.getTranslation(new Vector3f());
        Quaternionf facing = want.getNormalizedRotation(new Quaternionf());
        Vector3f shoulder = arm.getTranslation(new Vector3f());
        Vector3f elbow = hand.getTranslation(new Vector3f());
        Vector3f wrist = tool.getTranslation(new Vector3f());
        float upper = shoulder.distance(elbow);
        float lower = elbow.distance(wrist);
        Vector3f toTarget = new Vector3f(target).sub(shoulder);

        if (upper < 1.0e-4F || lower < 1.0e-4F || toTarget.lengthSquared() < 1.0e-8F) {
            return Float.NaN;
        }

        float reach = Math.max(Math.abs(upper - lower) + 1.0e-3F, Math.min(upper + lower - 1.0e-3F, toTarget.length()));
        Vector3f dir = new Vector3f(toTarget).normalize();

        // The elbow keeps bending the way it bends now.
        Vector3f bend = new Vector3f(elbow).sub(shoulder);
        bend.sub(new Vector3f(dir).mul(bend.dot(dir)));

        if (bend.lengthSquared() < 1.0e-6F) {
            Vector3f old = new Vector3f(wrist).sub(shoulder);
            bend = new Vector3f(dir).cross(new Vector3f(old).cross(dir));

            if (bend.lengthSquared() < 1.0e-6F) {
                bend = new Vector3f(dir).cross(new Vector3f(0.0F, 1.0F, 0.0F));
            }

            if (bend.lengthSquared() < 1.0e-6F) {
                bend = new Vector3f(1.0F, 0.0F, 0.0F);
            }
        }

        bend.normalize();
        float cos = (upper * upper + reach * reach - lower * lower) / (2.0F * upper * reach);
        cos = Math.max(-1.0F, Math.min(1.0F, cos));
        float sin = (float) Math.sqrt(Math.max(0.0F, 1.0F - cos * cos));
        Vector3f newElbow = new Vector3f(shoulder).add(new Vector3f(dir).mul(upper * cos)).add(new Vector3f(bend).mul(upper * sin));

        // Turn the upper arm about the shoulder so the elbow lands there,
        // carrying everything below it; then the forearm about the elbow
        // so the hand lands on the target.
        Quaternionf turnArm = new Quaternionf().rotationTo(new Vector3f(elbow).sub(shoulder), new Vector3f(newElbow).sub(shoulder));
        this.turnAbout(posed, armName, shoulder, turnArm);

        Vector3f elbowNow = posed.get(handName).getTranslation(new Vector3f());
        Vector3f wristNow = posed.get(toolName).getTranslation(new Vector3f());
        Quaternionf turnHand = new Quaternionf().rotationTo(new Vector3f(wristNow).sub(elbowNow), new Vector3f(target).sub(elbowNow));
        this.turnAbout(posed, handName, elbowNow, turnHand);

        // And the hand itself faces the way the animation meant it to.
        Vector3f wristEnd = posed.get(toolName).getTranslation(new Vector3f());
        posed.put(toolName, new Matrix4f().translation(wristEnd).rotate(facing));
        return wristEnd.distance(target);
    }

    /** Turns a joint about a point, and carries every joint below it along. */
    private void turnAbout(Map<String, Matrix4f> posed, String joint, Vector3f about, Quaternionf turn) {
        Matrix4f before = posed.get(joint);

        if (before == null) {
            return;
        }

        Matrix4f spin = new Matrix4f().translation(about).rotate(turn).translate(-about.x, -about.y, -about.z);
        Map<String, Matrix4f> old = new java.util.HashMap<>();

        for (String name : this.jointOrder) {
            if (this.isBelow(name, joint)) {
                old.put(name, posed.get(name));
            }
        }

        posed.put(joint, new Matrix4f(spin).mul(before));

        for (String name : this.jointOrder) {
            if (name.equals(joint) || !this.isBelow(name, joint)) {
                continue;
            }

            String parent = this.jointParent.get(name);
            Matrix4f parentOld = parent == null ? null : old.getOrDefault(parent, parent.equals(joint) ? before : null);
            Matrix4f mine = old.get(name);

            if (parentOld == null || mine == null || posed.get(parent) == null) {
                continue;
            }

            Matrix4f local = new Matrix4f(parentOld).invert().mul(mine);
            posed.put(name, new Matrix4f(posed.get(parent)).mul(local));
        }
    }

    /** Whether a joint is the named one or somewhere under it. */
    private boolean isBelow(String joint, String ancestor) {
        for (String at = joint; at != null; at = this.jointParent.get(at)) {
            if (at.equals(ancestor)) {
                return true;
            }
        }

        return false;
    }

    /** Depth-first insert so a bone never comes before the one above it. */
    private void addInOrder(YsmLiveSkeleton.LiveBone bone, int depth) {
        if (depth > 32 || this.ordered.contains(bone)) {
            return;
        }

        YsmLiveSkeleton.LiveBone parent = bone.parent() == null ? null : this.byName.get(key(bone.parent()));

        if (parent != null) {
            this.addInOrder(parent, depth + 1);
        }

        if (!this.ordered.contains(bone)) {
            this.ordered.add(bone);
        }
    }

    private void collectJoints(Joint joint, @Nullable String parent) {
        this.jointOrder.add(joint.getName());
        this.jointParent.put(joint.getName(), parent);
        this.jointByName.put(joint.getName(), joint);

        for (Joint child : joint.getSubJoints()) {
            this.collectJoints(child, joint.getName());
        }
    }

    /** Epic Fight's resting orientation for a joint. */
    private Quaternionf bindRotation(String joint) {
        Joint found = this.jointByName.get(joint);
        return found == null ? new Quaternionf() : new Quaternionf(rotationOf(found.getLocalTransform()));
    }

    /**
     * Whether the pivots this model keeps in memory are still written the
     * way the model file wrote them, with the left side on the positive
     * side, or already turned round to match Epic Fight.
     */
    private boolean mirrorX;

    /** Whether the model's sideways axis runs the other way from Epic Fight's, so that every transform is drawn mirrored. */
    public boolean mirrorsX() {
        return this.mirrorX;
    }

    /** How many of this model's own units make one of Epic Fight's blocks. */
    private static final float UNITS_PER_BLOCK = 16.0F / 0.7F;

    /** The same ruler, but the one this model is actually drawn with. */
    private static float drawnAt;

    public static void setModelScale(float value) {
        drawnAt = Float.isFinite(value) && value > 0.05F && value < 8.0F ? value : 0.0F;
    }

    /** The measured size, for anything that needs to undo it. Zero if unknown. */
    public static float modelScale() {
        return drawnAt;
    }

    private static float unitsPerBlock() {
        return drawnAt > 0.0F ? 16.0F / drawnAt : UNITS_PER_BLOCK;
    }

    private Vector3f pivotOf(YsmLiveSkeleton.LiveBone bone) {
        return new Vector3f(this.mirrorX ? -bone.pivotX() : bone.pivotX(), bone.pivotY(), bone.pivotZ());
    }

    /**
     * Where a bone actually rests, with the bones above it turned as the
     * model built them.
     */
    private Vector3f restWorldPivot(YsmLiveSkeleton.LiveBone bone) {
        return this.restWorld(bone).transformPosition(this.pivotOf(bone));
    }

    /** The whole resting frame of a bone, turn and place together. */
    private Matrix4f restWorld(YsmLiveSkeleton.LiveBone bone) {
        Matrix4f world = new Matrix4f();
        List<YsmLiveSkeleton.LiveBone> chain = new ArrayList<>();
        YsmLiveSkeleton.LiveBone at = bone;

        for (int step = 0; at != null && step < 32; step++) {
            chain.add(0, at);
            at = at.parent() == null ? null : this.byName.get(key(at.parent()));
        }

        for (YsmLiveSkeleton.LiveBone link : chain) {
            Vector3f pivot = this.pivotOf(link);
            float[] rest = this.restRotations.getOrDefault(key(link.name()), ZERO);
            world.translate(pivot.x, pivot.y, pivot.z)
                    .rotate(bedrock(rest))
                    .translate(-pivot.x, -pivot.y, -pivot.z);
        }

        return world;
    }

    static Quaternionf rotationOf(OpenMatrix4f matrix) {
        // The turn alone, whatever the size: an animation that shrinks a
        // joint to nothing - the way a character is made to vanish - hands
        // over a matrix of zeros, and a turn read straight off it is not a
        // number. Such a joint is taken as not turned at all.
        float lx = (float) Math.sqrt(matrix.m00 * matrix.m00 + matrix.m01 * matrix.m01 + matrix.m02 * matrix.m02);
        float ly = (float) Math.sqrt(matrix.m10 * matrix.m10 + matrix.m11 * matrix.m11 + matrix.m12 * matrix.m12);
        float lz = (float) Math.sqrt(matrix.m20 * matrix.m20 + matrix.m21 * matrix.m21 + matrix.m22 * matrix.m22);

        if (!(lx > 1.0e-5F) || !(ly > 1.0e-5F) || !(lz > 1.0e-5F)) {
            return new Quaternionf();
        }

        org.joml.Matrix3f basis = new org.joml.Matrix3f(
                matrix.m00 / lx, matrix.m01 / lx, matrix.m02 / lx,
                matrix.m10 / ly, matrix.m11 / ly, matrix.m12 / ly,
                matrix.m20 / lz, matrix.m21 / lz, matrix.m22 / lz);
        Quaternionf turn = new Quaternionf().setFromNormalized(basis);

        if (!Float.isFinite(turn.x) || !Float.isFinite(turn.y) || !Float.isFinite(turn.z) || !Float.isFinite(turn.w)
                || turn.x * turn.x + turn.y * turn.y + turn.z * turn.z + turn.w * turn.w < 1.0e-6F) {
            return new Quaternionf();
        }

        return turn.normalize();
    }

    @Nullable
    private Vector3f rawPlaceOf(String joint) {
        for (String candidate : PIVOT_SOURCES.getOrDefault(joint, new String[0])) {
            YsmLiveSkeleton.LiveBone bone = this.byName.get(key(candidate));

            if (bone != null) {
                return this.restWorldPivot(bone);
            }
        }

        return null;
    }

    /**
     * Which joint carries this bone, exactly as the converted models decide
     * it: the bone's own name if it is one worth reading, otherwise the
     * nearest name above it, and failing that the joint it rests closest
     * to - a prop is a prop, and the only sensible home for one is whatever
     */
    @Nullable
    private String jointFor(YsmLiveSkeleton.LiveBone bone, Map<String, Vector3f> places) {
        YsmLiveSkeleton.LiveBone at = bone;
        String inherited = null;

        for (int step = 0; at != null && step < 32; step++) {
            inherited = BONE_TO_JOINT.get(key(at.name()));

            if (inherited != null) {
                break;
            }

            at = at.parent() == null ? null : this.byName.get(key(at.parent()));
        }

        Matrix4f rest = this.restWorldByBone.get(key(bone.name()));

        if (rest == null) {
            return inherited;
        }

        // A bone is only driven when this mod can say where it belongs.
        // Yes Steve Model does not hand over the whole hierarchy, and a
        if (!this.chainKnown(bone)) {
            return null;
        }

        Vector3f where = rest.transformPosition(this.pivotOf(bone));

        // A bone whose name - or whose parent's, or its parent's - says what
        // part of the body it is gets to be believed, however far it reaches.
        if (inherited != null) {
            return inherited;
        }

        // A bone that carries part of the body is not a decoration, and it
        // must not be placed by where it happens to rest. Nearly every model
        if (bone.parent() == null && this.below(bone) > 8) {
            return "Root";
        }

        if (this.carries.contains(key(bone.name())) || atOrigin(where, places.get("Head"))
                || this.below(bone) > 8) {
            return "Torso";
        }

        String best = this.nearest(where, ANY_JOINT, places, true);
        return best != null ? best : this.nearest(where, BODY_JOINTS, places, false);
    }

    /** Whether a bone's pivot says nothing about where the bone is. */
    private static boolean atOrigin(Vector3f where, @Nullable Vector3f head) {
        return head != null && head.y > 0.01F && where.length() < 0.05F * head.y;
    }

    /** Bones with a named part of the body somewhere below them. */
    private final java.util.Set<String> carries = new java.util.HashSet<>();

    /** How many bones hang below this one, counted once and kept. */
    private final Map<String, Integer> subtree = new HashMap<>();

    private int below(YsmLiveSkeleton.LiveBone bone) {
        return this.subtree.getOrDefault(key(bone.name()), 0);
    }

    /** A bone that is a bag for other bones rather than a part of the body. */
    private boolean container(YsmLiveSkeleton.LiveBone bone) {
        if (this.below(bone) <= 8) {
            return false;
        }

        Vector3f pivot = this.pivotOf(bone);
        float height = this.modelHeight();
        return height > 0.01F && pivot.length() < 0.05F * height;
    }

    private void countSubtrees() {
        for (YsmLiveSkeleton.LiveBone bone : this.ordered) {
            YsmLiveSkeleton.LiveBone at = bone.parent() == null ? null : this.byName.get(key(bone.parent()));

            for (int step = 0; at != null && step < 32; step++) {
                this.subtree.merge(key(at.name()), 1, Integer::sum);
                at = at.parent() == null ? null : this.byName.get(key(at.parent()));
            }
        }
    }

    private void findCarriers() {
        for (YsmLiveSkeleton.LiveBone bone : this.ordered) {
            if (!BONE_TO_JOINT.containsKey(key(bone.name()))) {
                continue;
            }

            YsmLiveSkeleton.LiveBone at = bone.parent() == null ? null : this.byName.get(key(bone.parent()));

            for (int step = 0; at != null && step < 32; step++) {
                this.carries.add(key(at.name()));
                at = at.parent() == null ? null : this.byName.get(key(at.parent()));
            }
        }
    }

    /** Bones that others hang from: a real root rather than an orphan. */
    private final java.util.Set<String> roots = new java.util.HashSet<>();

    /** Whether every bone above this one, up to a root, is accounted for. */
    private boolean chainKnown(YsmLiveSkeleton.LiveBone bone) {
        YsmLiveSkeleton.LiveBone at = bone;

        for (int step = 0; step < 32; step++) {
            String above = at.parent();

            if (above == null) {
                // A bone with nothing above it is either the model's own
                // root - which other bones hang from, so it is real - or a
                return step > 0 || this.roots.contains(key(at.name()));
            }

            YsmLiveSkeleton.LiveBone parent = this.byName.get(key(above));

            if (parent == null) {
                return false;
            }

            at = parent;
        }

        return false;
    }

    private static boolean isBody(String joint) {
        for (String body : BODY_JOINTS) {
            if (body.equals(joint)) {
                return true;
            }
        }

        return false;
    }

    /** Whichever of these joints the given spot rests closest to. */
    @Nullable
    private String nearest(Vector3f where, String[] candidates, Map<String, Vector3f> places, boolean ownBoneOnly) {
        String best = null;
        float nearest = Float.MAX_VALUE;

        for (String joint : candidates) {
            if (ownBoneOnly && !this.sourced.contains(joint)) {
                continue;
            }

            Vector3f place = places.get(joint);

            if (place == null) {
                continue;
            }

            float distance = place.distance(where);

            if (distance < nearest) {
                nearest = distance;
                best = joint;
            }
        }

        return best;
    }

    /** How this model measures up against Epic Fight's own skeleton. */
    private float modelScale(Map<String, Vector3f> biped) {
        Vector3f bipedHead = biped.get("Head");
        Vector3f ourHead = this.rawPlaceOf("Head");

        if (bipedHead == null || bipedHead.y < 0.01F || ourHead == null || ourHead.y <= 0.01F) {
            return unitsPerBlock();
        }

        // How tall this model is next to Epic Fight's, as a plain ratio -
        // no units in it, so the sanity limits below mean the same thing
        float tall = ourHead.y / (bipedHead.y * unitsPerBlock());
        return Math.max(0.05F, Math.min(5.0F, tall)) * unitsPerBlock();
    }

    @Nullable
    private Vector3f fallbackPlace(String joint, Map<String, Matrix4f> bindWorld) {
        String parent = this.jointParent.get(joint);
        Matrix4f parentWorld = parent == null ? null : bindWorld.get(parent);
        return parentWorld == null ? null : parentWorld.getTranslation(new Vector3f());
    }

    /** Where Epic Fight's own joints rest, in its own space. */
    private void collectBipedPlaces(Joint joint, Matrix4f parentWorld, Map<String, Vector3f> out) {
        OpenMatrix4f local = joint.getLocalTransform();
        Matrix4f step = new Matrix4f().translation(new Vector3f(local.m30, local.m31, local.m32))
                .rotate(rotationOf(local));
        Matrix4f world = new Matrix4f(parentWorld).mul(step);
        Vector3f place = world.getTranslation(new Vector3f());

        // Taken as it stands. The file this skeleton comes from is written
        // the way it was modelled, with the upright axis last, and it would
        out.put(joint.getName(), new Vector3f(place));

        for (Joint child : joint.getSubJoints()) {
            this.collectBipedPlaces(child, world, out);
        }
    }

    /** The three joints no model has a bone for. */
    private void placeBody(Map<String, Vector3f> places) {
        Vector3f thighL = places.get("Thigh_L");
        Vector3f thighR = places.get("Thigh_R");

        if (thighL != null && thighR != null) {
            // Sideways the hips sit on the middle of the model, not on the
            // middle of the two thighs: a model whose legs are not evenly
            // spaced would otherwise stand off its own centre.
            places.put("Root", new Vector3f(0.0F,
                    (thighL.y + thighR.y) * 0.5F, (thighL.z + thighR.z) * 0.5F));
        }

        Vector3f root = places.get("Root");
        Vector3f chest = places.get("Chest");

        if (root != null && chest != null) {
            places.put("Torso", new Vector3f(0.0F, root.y + (chest.y - root.y) * 0.143F,
                    root.z + (chest.z - root.z) * 0.143F));
        }

        Vector3f head = places.get("Head");

        if (head != null) {
            places.put("Shoulder_L", new Vector3f(0.0F, head.y, head.z));
            places.put("Shoulder_R", new Vector3f(0.0F, head.y, head.z));
        }
    }

    /** Puts back the joints a model has no sensible bone for. */
    private void repair(Map<String, Vector3f> places, Map<String, Vector3f> biped, float scale) {
        Vector3f bipedHead = biped.get("Head");

        if (bipedHead == null) {
            return;
        }

        // The height a head ought to reach on a model this size. Reading it
        // off the model's own head instead means a model that names no head
        // is never checked at all.
        float head = bipedHead.y * scale;

        if (!(head > 0.01F)) {
            return;
        }

        java.util.Set<String> bad = new java.util.HashSet<>();

        for (String joint : this.jointOrder) {
            Vector3f ours = places.get(joint);
            Vector3f want = biped.get(joint);

            if (ours == null || want == null) {
                bad.add(joint);
                continue;
            }

            float expected = want.y * scale;

            if (!Float.isFinite(ours.x) || !Float.isFinite(ours.y) || !Float.isFinite(ours.z)
                    || Math.abs(ours.y - expected) > 0.40F * head
                    || Math.abs(ours.x) > 0.9F * head || Math.abs(ours.z) > 0.9F * head) {
                bad.add(joint);
            }
        }

        // A limb whose two sides rest at different heights is a limb one of
        // whose bones was read off the wrong thing. The converted models
        for (String limb : new String[] { "Thigh", "Knee", "Leg", "Shoulder", "Arm", "Elbow", "Hand", "Tool" }) {
            Vector3f leftSide = places.get(limb + "_L");
            Vector3f rightSide = places.get(limb + "_R");

            if (leftSide != null && rightSide != null
                    && Math.abs(leftSide.y - rightSide.y) > 0.25F * head) {
                bad.add(limb + "_L");
                bad.add(limb + "_R");
            }
        }

        for (String[] chain : JOINT_CHAINS) {
            boolean anyBad = false;

            for (String joint : chain) {
                anyBad |= bad.contains(joint);
            }

            if (!anyBad) {
                continue;
            }

            for (String joint : chain) {
                Vector3f want = biped.get(joint);

                if (want != null) {
                    places.put(joint, new Vector3f(want).mul(scale));
                    this.repaired++;
                }
            }
        }
    }

    /** Which joint carries each bone, for the log and for checking. */
    public Map<String, String> boneJoints() {
        return this.boneJoint;
    }

    /** The resting skeleton this solver built, for checking against. */
    private final Map<String, Vector3f> bindPlaces = new LinkedHashMap<>();

    public Map<String, Vector3f> bindPlaces() {
        return this.bindPlaces;
    }

    /** The joints that had a bone of the model's own to sit on; the rest were taken from the biped. */
    public java.util.Set<String> sourcedJoints() {
        return this.sourced;
    }

    /** How many of this model's units one of Epic Fight's blocks is, by the head's height. */
    public float unitsPerBipedBlock() {
        return this.bipedScale;
    }

    /** Where a bone rests, whole, in the model's units - after every turn the bones above it were built with. */
    @Nullable
    public Vector3f restPlaceOf(String boneName) {
        YsmLiveSkeleton.LiveBone bone = this.byName.get(key(boneName));
        return bone == null ? null : this.restWorldPivot(bone);
    }

    private int repaired;

    public int repaired() {
        return this.repaired;
    }

    private static final String[][] JOINT_CHAINS = {
            { "Root", "Torso", "Chest", "Head" },
            { "Shoulder_L", "Arm_L", "Elbow_L", "Hand_L", "Tool_L" },
            { "Shoulder_R", "Arm_R", "Elbow_R", "Hand_R", "Tool_R" },
            { "Thigh_L", "Knee_L", "Leg_L" },
            { "Thigh_R", "Knee_R", "Leg_R" } };

    /**
     * Where every driven bone has to be this frame.
     *
     * @param pose  Epic Fight's pose for this instant
     * @param rests each bone's own resting rotation, as the model stores it
     */
    public Map<String, Placement> solve(Pose pose, Map<String, float[]> rests) {
        Map<String, Placement> out = new HashMap<>();
        Map<String, Matrix4f> wanted = new HashMap<>();

        if (!this.ready) {
            return out;
        }

        Map<String, Matrix4f> posed = new LinkedHashMap<>();

        for (String joint : this.jointOrder) {
            String parent = this.jointParent.get(joint);
            Matrix4f parentWorld = parent == null ? new Matrix4f() : posed.get(parent);
            Quaternionf delta = localRotation(pose, joint);

            // An animation moves joints as well as turning them - a lunge
            // carries the whole body forward and a crouch drops it. Leaving
            Matrix4f frame = new Matrix4f(parentWorld).mul(this.jointBindLocal.get(joint));
            Vector3f shift = travel ? localTranslation(pose, joint) : new Vector3f();

            if ("Root".equals(joint)) {
                this.rootTravel = new Vector3f(shift).mul(unitsPerBlock());
            }

            if (shift.x == 0.0F && shift.y == 0.0F && shift.z == 0.0F) {
                posed.put(joint, frame.rotate(delta));
                continue;
            }

            // Straight into the joint's own frame, the way Epic Fight
            // applies it. An earlier version turned this distance sideways
            float ruler = unitsPerBlock();
            posed.put(joint, frame.translate(shift.x * ruler, shift.y * ruler, shift.z * ruler)
                    .rotate(delta));
        }

        // One hand against the other, the way the animation meant it.
        try {
            this.meetHands(posed, pose);
        } catch (Throwable t) {
            EpicYsm.LOGGER.debug("Could not bring the hands together", t);
        }

        // The model's own chain, bone by bone, the way a bedrock model is
        // built: move to the pivot, turn, move back. A driven bone is put
        // where Epic Fight wants its joint; every other bone keeps its rest.
        Map<String, Matrix4f> boneWorld = new HashMap<>();

        for (YsmLiveSkeleton.LiveBone bone : this.ordered) {
            Matrix4f parentWorld = bone.parent() == null ? new Matrix4f()
                    : boneWorld.getOrDefault(key(bone.parent()), new Matrix4f());
            Vector3f pivot = this.pivotOf(bone);
            String joint = (carryAll ? this.boneJoint : this.namedJoint).get(key(bone.name()));
            Matrix4f bind = joint == null ? null : this.jointBindWorld.get(joint);
            Matrix4f rest = joint == null ? null : this.restWorldByBone.get(key(bone.name()));
            Matrix4f target = joint == null || bind == null || rest == null ? null
                    : new Matrix4f(posed.get(joint)).mul(new Matrix4f(bind).invert()).mul(rest);
            Quaternionf rotation;
            Vector3f offset;

            if (target == null) {
                float[] own = rests.getOrDefault(bone.name(), ZERO);
                rotation = fromRest(own);
                offset = new Vector3f();
            } else {
                // A bone is carried by its joint, not dropped onto it. The
                // converted models move a bone by the difference between
                Matrix4f inverseParent = new Matrix4f(parentWorld).invert();
                rotation = inverseParent.getNormalizedRotation(new Quaternionf())
                        .mul(target.getNormalizedRotation(new Quaternionf()));
                offset = inverseParent.transformPosition(target.transformPosition(new Vector3f(pivot)))
                        .sub(pivot);

                // Except for the one bone the whole model hangs from.
                //
                if (rootOnTheSpot && bone.parent() == null && "Root".equals(joint)) {
                    // Where it rests, moved by exactly as far as its joint
                    // has moved, and then read in its parent's frame like
                    Vector3f travelled = posed.get(joint).getTranslation(new Vector3f())
                            .sub(bind.getTranslation(new Vector3f()));
                    offset = inverseParent.transformPosition(
                            rest.transformPosition(new Vector3f(pivot)).add(travelled)).sub(pivot);
                }
                Vector3f written = this.mirrorX ? new Vector3f(-offset.x, offset.y, offset.z) : offset;
                out.put(bone.name(), new Placement(rotation, written));
                wanted.put(key(bone.name()), target);
            }

            boneWorld.put(key(bone.name()), new Matrix4f(parentWorld)
                    .translate(pivot.x + offset.x, pivot.y + offset.y, pivot.z + offset.z)
                    .rotate(rotation)
                    .translate(-pivot.x, -pivot.y, -pivot.z));
        }

        // Nothing of this frame is written when any of it is not a number:
        // a bone given such a place is drawn nowhere, and the number sticks
        // in whatever keeps a memory of the last frame.
        for (Placement placement : out.values()) {
            Vector3f offset = placement.offset();
            Quaternionf turn = placement.rotation();

            if (!Float.isFinite(offset.x) || !Float.isFinite(offset.y) || !Float.isFinite(offset.z)
                    || !Float.isFinite(turn.x) || !Float.isFinite(turn.y) || !Float.isFinite(turn.z) || !Float.isFinite(turn.w)) {
                if (!this.saidNotANumber) {
                    this.saidNotANumber = true;
                    EpicYsm.LOGGER.info("Skeleton solver: the pose had no numbers in it this frame (a joint scaled to nothing);"
                            + " nothing was written, the model keeps its last pose for the frame");
                }

                return new HashMap<>();
            }
        }

        this.posedJoints = posed;
        this.posedBones = boneWorld;
        this.selfCheck(wanted, boneWorld);
        this.reportSwing(wanted, posed);
        this.watchBones(boneWorld);
        this.watchJump(out);
        return out;
    }

    private boolean saidNotANumber;

    /** How far the animation carried the root joint this frame, in the model's units, for the log. */
    private Vector3f rootTravel = new Vector3f();

    public Vector3f rootTravel() {
        return this.rootTravel;
    }

    /** Whether the root bone turns on the spot rather than about its joint. */
    private static boolean rootOnTheSpot;

    public static void setRootOnTheSpot(boolean value) {
        rootOnTheSpot = value;
    }

    private Vector3f lastRootOffset;
    private float worstJump;
    private String worstJumpBone = "";
    private int jumpFrames;
    private boolean saidJump;

    /** Catches the model leaping sideways. */
    /** Whether the picture being drawn right now is the world. */
    private static boolean worldFrame = true;

    public static void setWorldFrame(boolean value) {
        worldFrame = value;
    }

    public static boolean worldFrame() {
        return worldFrame;
    }

    private void watchJump(Map<String, Placement> placements) {
        if (!worldFrame) {
            return;
        }

        String root = null;

        for (YsmLiveSkeleton.LiveBone bone : this.ordered) {
            if (bone.parent() == null && this.subtree.getOrDefault(key(bone.name()), 0) > 0) {
                root = bone.name();
                break;
            }
        }

        Placement placement = root == null ? null : placements.get(root);

        if (placement == null) {
            return;
        }

        Vector3f offset = new Vector3f(placement.offset());

        if (this.lastRootOffset != null) {
            float jump = offset.distance(this.lastRootOffset);

            if (jump > this.worstJump) {
                this.worstJump = jump;
                this.worstJumpBone = root;
            }

            // Nothing is held back any more, and this is why.
            //
        }

        this.lastRootOffset = new Vector3f(offset);

        // Where a step came from, so the next one does not have to be
        // guessed at. The bone is moved for two reasons and only two: the
        String joint = this.boneJoint.get(key(root));
        Matrix4f drive = joint == null || this.posedJoints == null ? null : this.posedJoints.get(joint);

        if (drive != null) {
            Vector3f now = drive.getTranslation(new Vector3f());

            if (this.lastJointPlace != null) {
                this.worstJointStep = Math.max(this.worstJointStep, now.distance(this.lastJointPlace));
            }

            this.lastJointPlace = now;
        }

        if (++this.jumpFrames >= 600 && !this.saidJump) {
            this.saidJump = true;
            Matrix4f bind = joint == null ? null : this.jointBindWorld.get(joint);
            Vector3f lever = bind == null ? new Vector3f() : bind.getTranslation(new Vector3f());
            com.argorice.epicysm.client.Diag.info("Skeleton solver: over {} frames the bone the model hangs from ({}, driven by {})"
                    + " never moved more than {} model pixels between one frame and the next, while the joint"
                    + " driving it never moved more than {}. It was held back {} times out of {}. The joint"
                    + " rests {} pixels off the bone's own pivot, and a turn of the joint moves the bone by"
                    + " that much for every radian. A model is about {} pixels tall.", this.jumpFrames,
                    this.worstJumpBone, joint, Math.round(this.worstJump * 100.0F) / 100.0F,
                    Math.round(this.worstJointStep * 100.0F) / 100.0F, this.jumpsOverLimit, this.jumpFrames,
                    Math.round(lever.length() * 100.0F) / 100.0F, Math.round(this.modelHeight()));
            this.sayJudder();
        }
    }

    /**
     * The same question asked of every bone this mod drives, not just the
     * root.
     */
    private final Map<String, Vector3f> lastPlace = new HashMap<>();
    private final Map<String, Float> worstStep = new HashMap<>();
    private final Map<String, Float> worstDrive = new HashMap<>();

    private void watchBones(Map<String, Matrix4f> boneWorld) {
        if (!worldFrame) {
            return;
        }

        for (YsmLiveSkeleton.LiveBone bone : this.ordered) {
            String name = key(bone.name());
            String joint = (carryAll ? this.boneJoint : this.namedJoint).get(name);
            Matrix4f found = boneWorld.get(name);

            if (joint == null || found == null) {
                continue;
            }

            Vector3f pivot = this.pivotOf(bone);
            this.step(name, found.transformPosition(new Vector3f(pivot)), this.worstStep);
            Matrix4f drive = this.posedJoints == null ? null : this.posedJoints.get(joint);

            if (drive != null) {
                this.step(name + " joint", drive.getTranslation(new Vector3f()), this.worstDrive);
            }
        }
    }

    private void step(String key, Vector3f now, Map<String, Float> worst) {
        Vector3f before = this.lastPlace.get(key);

        if (before != null) {
            float moved = now.distance(before);

            if (moved > worst.getOrDefault(key, 0.0F)) {
                worst.put(key, moved);
            }
        }

        this.lastPlace.put(key, now);
    }

    private void sayJudder() {
        List<Map.Entry<String, Float>> ranked = new ArrayList<>(this.worstStep.entrySet());
        ranked.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
        StringBuilder said = new StringBuilder();

        for (int i = 0; i < Math.min(6, ranked.size()); i++) {
            Map.Entry<String, Float> entry = ranked.get(i);
            float drive = this.worstDrive.getOrDefault(entry.getKey() + " joint", 0.0F);
            said.append(said.length() == 0 ? "" : ", ").append(entry.getKey()).append(' ')
                    .append(Math.round(entry.getValue() * 100.0F) / 100.0F).append(" (its joint ")
                    .append(this.boneJoint.get(entry.getKey())).append(' ')
                    .append(Math.round(drive * 100.0F) / 100.0F).append(')');
        }

        com.argorice.epicysm.client.Diag.info("Skeleton solver: the furthest each driven bone moved between two frames, worst"
                + " first, in model pixels, with what its own joint did beside it: {}. Alike is Epic Fight's"
                + " animation; a bone well ahead of its joint is this mod's arithmetic.", said);
    }

    private Vector3f lastJointPlace;
    private float worstJointStep;
    private int jumpsOverLimit;

    private float modelHeight() {
        Vector3f head = this.bindPlaces.get("Head");
        return head == null ? 0.0F : head.y;
    }

    /** How tall this model is, in its own units, for a sanity check. */
    public float height() {
        return this.modelHeight();
    }

    /** Where a bone that this mod does not drive is carried to by one it does. */
    @Nullable
    public Matrix4f carriedAsDrawn(String boneName, String riderName) {
        Matrix4f found = this.posedBones.get(key(boneName));
        YsmLiveSkeleton.LiveBone rider = this.byName.get(key(riderName));

        if (found == null || rider == null) {
            return null;
        }

        Vector3f seat = this.pivotOf(rider);
        return this.asDrawn(new Matrix4f(found).translate(seat.x, seat.y, seat.z));
    }

    /**
     * Where Epic Fight's joints ended up this frame, in the model's own
     * pixels - kept so the item in the player's hand can be drawn on the
     * joint that holds it, exactly where Epic Fight would draw it.
     */
    private Map<String, Matrix4f> posedJoints = Map.of();

    public Map<String, Matrix4f> posedJoints() {
        return this.posedJoints;
    }

    /** Where the model's own bones ended up this frame, in its own pixels. */
    private Map<String, Matrix4f> posedBones = Map.of();

    /** One of the model's own bones, in the space it is drawn in. */
    @Nullable
    public Matrix4f boneAsDrawn(String boneName) {
        Matrix4f found = this.posedBones.get(key(boneName));
        YsmLiveSkeleton.LiveBone bone = this.byName.get(key(boneName));

        if (found == null || bone == null) {
            return null;
        }

        // A bone's matrix moves points around its pivot and back, so its own
        // translation is not where the bone is - the bone is where its pivot
        Vector3f pivot = this.pivotOf(bone);
        return this.asDrawn(new Matrix4f(found).translate(pivot.x, pivot.y, pivot.z));
    }

    private static final Matrix4f MIRROR = new Matrix4f().scaling(-1.0F, 1.0F, 1.0F);

    /**
     * The same joints, in the space Yes Steve Model actually draws in:
     * its own sideways axis, and blocks rather than model pixels.
     */
    public Map<String, Matrix4f> posedJointsAsDrawn() {
        Map<String, Matrix4f> out = new LinkedHashMap<>();

        for (Map.Entry<String, Matrix4f> entry : this.posedJoints.entrySet()) {
            out.put(entry.getKey(), this.asDrawn(entry.getValue()));
        }

        return out;
    }

    private Matrix4f asDrawn(Matrix4f from) {
        Matrix4f m = new Matrix4f(from);

        if (this.mirrorX) {
            m = MIRROR.mul(m, new Matrix4f()).mul(MIRROR);
        }

        m.setTranslation(m.m30() / 16.0F, m.m31() / 16.0F, m.m32() / 16.0F);
        return m;
    }

    private int swings;
    private float peak;
    private int frames;
    private String peakLine = "";

    /** The peak of a swing, both for the bone and for the joint driving it. */
    private void reportSwing(Map<String, Matrix4f> wanted, Map<String, Matrix4f> posed) {
        if (this.swings > 0) {
            return;
        }

        this.frames++;

        for (YsmLiveSkeleton.LiveBone bone : this.ordered) {
            String joint = this.boneJoint.get(key(bone.name()));

            if (!"Tool_R".equals(joint) && !"Hand_R".equals(joint)) {
                continue;
            }

            Matrix4f target = wanted.get(key(bone.name()));
            Matrix4f rest = this.restWorldByBone.get(key(bone.name()));
            Matrix4f jointNow = posed.get(joint);
            Matrix4f jointRest = this.jointBindWorld.get(joint);

            if (target == null || rest == null || jointNow == null || jointRest == null) {
                return;
            }

            Vector3f pivot = this.pivotOf(bone);
            Vector3f from = rest.transformPosition(new Vector3f(pivot));
            Vector3f to = target.transformPosition(new Vector3f(pivot));
            float moved = from.distance(to);

            if (moved > this.peak) {
                this.peak = moved;
                Vector3f jointFrom = jointRest.getTranslation(new Vector3f());
                Vector3f jointTo = jointNow.getTranslation(new Vector3f());
                this.peakLine = String.format(java.util.Locale.ROOT,
                        "Swing: at its widest the bone %s moves sideways %.2f, up %.2f, forward %.2f model"
                        + " pixels, while Epic Fight's joint %s moves sideways %.2f, up %.2f, forward %.2f."
                        + " Forward is negative - the way the model faces. The two should agree in sign.",
                        bone.name(), to.x - from.x, to.y - from.y, to.z - from.z, joint,
                        jointTo.x - jointFrom.x, jointTo.y - jointFrom.y, jointTo.z - jointFrom.z);
            }

            if (this.frames > 400 && this.peak > 1.0F) {
                this.swings++;
                com.argorice.epicysm.client.Diag.info(this.peakLine);
            }

            return;
        }
    }

    private boolean checked;

    /**
     * Rebuilds the chain from what was just written and asks whether every
     * driven bone actually landed on the joint it was aimed at.
     */
    private void selfCheck(Map<String, Matrix4f> wanted, Map<String, Matrix4f> boneWorld) {
        if (this.checked) {
            return;
        }

        this.checked = true;
        float worst = 0.0F;
        String where = "";
        int parented = 0;

        for (YsmLiveSkeleton.LiveBone bone : this.ordered) {
            if (bone.parent() != null) {
                parented++;
            }

            Matrix4f target = wanted.get(key(bone.name()));
            Matrix4f built = boneWorld.get(key(bone.name()));

            if (target == null || built == null) {
                continue;
            }

            Vector3f want = target.transformPosition(new Vector3f(this.pivotOf(bone)));
            Vector3f landed = built.transformPosition(this.pivotOf(bone));
            float error = want.distance(landed);

            if (error > worst) {
                worst = error;
                where = bone.name();
            }
        }

        com.argorice.epicysm.client.Diag.info("Skeleton solver: {} of {} bone(s) know which bone is above them; the worst bone"
                + " misses the joint it was aimed at by {} model pixels{}", parented, this.ordered.size(),
                Math.round(worst * 1000.0F) / 1000.0F, where.isEmpty() ? "" : " (" + where + ")");

        // The bone the whole model hangs from, and what drives it. Worth a
        // line of its own: when this said a foot, every step of a run threw
        // the model with the foot, and nothing else in the log showed it.
        for (YsmLiveSkeleton.LiveBone bone : this.ordered) {
            if (bone.parent() == null && this.subtree.getOrDefault(key(bone.name()), 0) > 0) {
                com.argorice.epicysm.client.Diag.info("Skeleton solver: the model hangs from {}, with {} bone(s) below it, and Epic"
                        + " Fight's {} drives it", bone.name(), this.below(bone),
                        this.boneJoint.getOrDefault(key(bone.name()), "nothing"));
                break;
            }
        }
    }

    private static final float[] ZERO = new float[3];

    /** A bone's own rest, read back from the three numbers the model keeps. */
    private static Quaternionf fromRest(float[] rest) {
        return bedrock(rest);
    }

    /** The turn Yes Steve Model makes out of the three numbers on a bone. */
    private static Quaternionf bedrock(float[] rest) {
        return new Quaternionf().rotateZ(rest[2]).rotateY(rest[1]).rotateX(rest[0]);
    }

    /** How far this animation moves a joint, in Epic Fight's own blocks. */
    private static Vector3f localTranslation(Pose pose, String joint) {
        try {
            if (!pose.hasTransform(joint)) {
                return new Vector3f();
            }

            OpenMatrix4f moved = pose.orElseEmpty(joint).toMatrix();
            return moved == null ? new Vector3f() : new Vector3f(moved.m30, moved.m31, moved.m32);
        } catch (Throwable t) {
            return new Vector3f();
        }
    }

    /** The turn an animation adds at one joint, this instant. */
    private static Quaternionf localRotation(Pose pose, String joint) {
        try {
            if (!pose.hasTransform(joint)) {
                return new Quaternionf();
            }

            OpenMatrix4f turned = pose.orElseEmpty(joint).toMatrix();
            return turned == null ? new Quaternionf() : rotationOf(turned);
        } catch (Throwable t) {
            return new Quaternionf();
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
