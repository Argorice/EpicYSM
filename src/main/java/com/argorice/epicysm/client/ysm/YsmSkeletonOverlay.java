package com.argorice.epicysm.client.ysm;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import com.argorice.epicysm.EpicYsm;

/** Puts Epic Fight's pose on the skeleton Yes Steve Model itself is drawing. */
public final class YsmSkeletonOverlay {
    private static final YsmSkeletonOverlay INSTANCE = new YsmSkeletonOverlay();

    public static YsmSkeletonOverlay get() {
        return INSTANCE;
    }

    private static final String YSM_PACKAGE = "com.elfmcys.";
    private static final int DISCOVERY_NODES = 200_000;
    private static final int DISCOVERY_DEPTH = 16;
    private static final int MIN_BONES = 5;
    // Long enough to see the numbers move, short enough that a model does
    // not stand there unposed while it is counted.
    private static final int LIVENESS_FRAMES = 60;

    /** Roles of the standard Yes Steve Model humanoid skeleton. */
    private enum Role {
        ROOT,
        BODY,
        LOWER,
        CHEST_LOW,
        CHEST,
        HEAD_OUTER,
        HEAD_INNER,
        ARM_L,
        FOREARM_L,
        HAND_L,
        ARM_R,
        FOREARM_R,
        HAND_R,
        THIGH_L,
        SHIN_L,
        FOOT_L,
        THIGH_R,
        SHIN_R,
        FOOT_R
    }

    private static final Map<String, Role> ROLE_BY_NAME = new LinkedHashMap<>();

    static {
        role("root", Role.ROOT);
        role("mroot", Role.ROOT);
        role("allbody", Role.BODY);
        role("mallbody", Role.BODY);
        role("body", Role.BODY);
        role("downbody", Role.LOWER);
        role("upbody", Role.CHEST_LOW);
        role("mupbody", Role.CHEST_LOW);
        role("upperbody", Role.CHEST);
        role("mupperbody", Role.CHEST);
        role("allhead", Role.HEAD_OUTER);
        role("mhead", Role.HEAD_OUTER);
        role("head", Role.HEAD_INNER);
        role("leftarm", Role.ARM_L);
        role("leftforearm", Role.FOREARM_L);
        role("lefthand", Role.HAND_L);
        role("rightarm", Role.ARM_R);
        role("rightforearm", Role.FOREARM_R);
        role("righthand", Role.HAND_R);
        role("leftleg", Role.THIGH_L);
        role("leftlowerleg", Role.SHIN_L);
        role("leftfoot", Role.FOOT_L);
        role("rightleg", Role.THIGH_R);
        role("rightlowerleg", Role.SHIN_R);
        role("rightfoot", Role.FOOT_R);
    }

    private static void role(String boneName, Role value) {
        ROLE_BY_NAME.put(boneName, value);
    }

    /* ------------------------------------------------------------------
     * Forms: one model, several bodies
     * ------------------------------------------------------------------ */

    /**
     * Some models carry more than one body: a big one and a small one, or
     * a human and the thing it turns into. Yes Steve Model shows one at a
     * time by scaling the others to nothing, and the bones of the second
     * body are named like the first with a mark on them - LeftArm2, Root3,
     */
    private static final String[] FORM_PREFIXES = { "big_", "big", "small_", "small", "mini_", "mini",
            "large_", "large", "little_", "little" };

    /** Which form a bone belongs to: "" for the plain one, else its mark. */
    static String formKey(String name) {
        String lower = name.toLowerCase(Locale.ROOT);

        if (bodyName(lower)) {
            return "";
        }

        for (String prefix : FORM_PREFIXES) {
            if (lower.startsWith(prefix) && bodyName(lower.substring(prefix.length()))) {
                return prefix.endsWith("_") ? prefix.substring(0, prefix.length() - 1) : prefix;
            }
        }

        int end = lower.length();

        while (end > 0 && Character.isDigit(lower.charAt(end - 1))) {
            end--;
        }

        if (end < lower.length()) {
            String base = lower.substring(0, end);

            if (base.endsWith("_")) {
                base = base.substring(0, base.length() - 1);
            }

            if (bodyName(base)) {
                return lower.substring(end);
            }
        }

        return "";
    }

    /** The bone's name with its form mark taken off, in the model's own spelling. */
    static String baseName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);

        if (bodyName(lower)) {
            return name;
        }

        for (String prefix : FORM_PREFIXES) {
            if (lower.startsWith(prefix) && bodyName(lower.substring(prefix.length()))) {
                return name.substring(prefix.length());
            }
        }

        int end = name.length();

        while (end > 0 && Character.isDigit(name.charAt(end - 1))) {
            end--;
        }

        if (end < name.length()) {
            String base = name.substring(0, end);

            if (base.endsWith("_")) {
                base = base.substring(0, base.length() - 1);
            }

            if (bodyName(base.toLowerCase(Locale.ROOT))) {
                return base;
            }
        }

        return name;
    }

    private static boolean bodyName(String lower) {
        return ROLE_BY_NAME.containsKey(lower) || YsmLiveSkeleton.knownNames().contains(lower)
                || lower.equals("root") || lower.equals("mroot") || lower.equals("arm") || lower.equals("leg")
                || lower.equals("lefthandlocator") || lower.equals("righthandlocator");
    }

    /** The form being posed, and whether it is on screen at all. */
    private String form = "";
    private boolean dormant;
    private int sinceFormCheck;
    private boolean saidDormant;

    /** Every form's body bones, whatever form is posed, to see which is on screen. */
    private Map<String, List<Bone>> formBones = Map.of();
    private Map<String, Bone> parentBone = Map.of();

    /** Whether this bone is drawn: its scale, and every known bone above it, is not nothing. */
    private boolean shown(Bone bone) {
        Bone at = bone;

        for (int step = 0; at != null && step < 40; step++) {
            if (at.hasMatrix()) {
                float[] m = at.matrices();
                int i = at.matrixAt();

                if (Math.abs(m[i + 6]) < 1.0E-4F && Math.abs(m[i + 7]) < 1.0E-4F && Math.abs(m[i + 8]) < 1.0E-4F) {
                    return false;
                }
            }

            at = this.parentBone.get(at.name());
        }

        return true;
    }

    /** Whether a form's body is on screen: at least one of its body bones is drawn. */
    private boolean formShown(String key) {
        List<Bone> found = this.formBones.get(key);

        if (found == null || found.isEmpty()) {
            return false;
        }

        int drawn = 0;

        for (Bone bone : found) {
            if (this.shown(bone)) {
                drawn++;
            }
        }

        // Half of them, not one: a hidden body may keep a bone or two
        // shown for a hat that both forms share.
        return drawn * 2 >= found.size();
    }

    /**
     * Once in a while: is the body being posed still the one on screen?
     * Returns false when nothing should be written this frame.
     */
    private boolean formStillShown() {
        if (this.formBones.isEmpty()) {
            return true;
        }

        if (++this.sinceFormCheck % 20 != 0) {
            return !this.dormant;
        }

        boolean shown = this.formShown(this.form);

        if (shown) {
            if (this.dormant) {
                this.dormant = false;
                EpicYsm.LOGGER.info("Skeleton overlay: the form '{}' is back on screen; posing it again",
                        this.form.isEmpty() ? "plain" : this.form);
            }

            return true;
        }

        for (String other : this.formBones.keySet()) {
            if (!other.equals(this.form) && this.formShown(other)) {
                EpicYsm.LOGGER.info("Skeleton overlay: the model switched from form '{}' to form '{}'; reading"
                        + " the skeleton again for that one", this.form.isEmpty() ? "plain" : this.form,
                        other.isEmpty() ? "plain" : other);
                this.restore();
                this.reset();
                return false;
            }
        }

        if (!this.dormant) {
            this.dormant = true;

            if (!this.saidDormant) {
                this.saidDormant = true;
                EpicYsm.LOGGER.info("Skeleton overlay: no human form of this model is on screen - it has turned"
                        + " into something else - so Yes Steve Model is left to animate it alone until a"
                        + " human form is back.");
            }
        }

        return false;
    }

    private enum Stage {
        IDLE,
        MEASURING,
        ACTIVE,
        GIVEN_UP
    }

    /** One live bone of the skeleton YSM is drawing. */
    private record Bone(String name, Object target, Field rotation, float[] original,
                        int matrixAt, int quatAt, float[] matrices, float[] quaternions, float[] pivot) {
        @Nullable
        Vector3f vector() {
            try {
                return this.rotation.get(this.target) instanceof Vector3f vec ? vec : null;
            } catch (Throwable t) {
                return null;
            }
        }

        boolean hasMatrix() {
            return this.matrixAt >= 0 && this.matrices != null && this.matrixAt + 12 <= this.matrices.length;
        }

        boolean hasQuaternion() {
            return this.quatAt >= 0 && this.quaternions != null && this.quatAt + 4 <= this.quaternions.length;
        }
    }

    private Stage stage = Stage.IDLE;
    private UUID owner;
    private ResourceLocation subject;
    private Map<Role, List<Bone>> bones = Map.of();
    private List<Bone> allBones = List.of();

    /**
     * Every other bone of the same model - hair, skirts, props. They are
     * never posed by name, only carried along with the nearest body bone,
     * so that correcting the body does not leave them floating behind it.
     */
    private List<Bone> others = List.of();

    /**
     * Bones of the body that carry no joint of their own but sit between
     * the ones that do - Arm above both arms, Leg above both legs, Waist,
     * Breast, Collar, the spare Root a model keeps above its Root.
     */
    private List<Bone> structural = List.of();

    /** Every bone of the model Epic Fight does not drive, held for ALL. */
    private List<Bone> everythingElse = List.of();

    /**
     * The skeleton solver for this model: it builds the very armature the
     * converted models use, out of the pivots Yes Steve Model has in
     * memory, and says where each bone has to be. Writing what it returns
     * reproduces the converted path exactly; writing rotations alone left
     */
    @Nullable
    private YsmPoseSolver solver;

    /** Every live bone by name, and its own rest rotation. */
    private final Map<String, Bone> boneByName = new LinkedHashMap<>();
    private final Map<String, float[]> restByName = new LinkedHashMap<>();

    /** Liveness measurement: does anything here move between frames? */
    private float[] lastSample = new float[0];
    private boolean[] moved = new boolean[0];
    private final int[] movedInGroup = new int[3];
    private int frames;
    private int movedNumbers;
    private boolean probe;

    /**
     * Which part of the probe pose to force. One joint at a time is worth
     * having: if turning only the head leans the whole body, the rotation
     * is landing on a bone that is not the head, and no amount of fixing
     * the arithmetic will help.
     */
    public enum ProbePart {
        ALL,
        HEAD,
        ARM,
        BODY,
        RX,
        RY,
        RZ
    }

    private ProbePart probePart = ProbePart.ALL;
    private int announcedRawAxis = -1;

    /** What the overlay is currently doing, for the command to report. */
    public String stageName() {
        return this.stage + (this.stage == Stage.ACTIVE ? " (" + this.mode + ", " + this.allBones.size() + " bones)" : "");
    }

    public void setProbePart(ProbePart part) {
        this.probePart = part;
        this.announcedRawAxis = -1;
    }

    public ProbePart probePart() {
        return this.probePart;
    }

    /** How many times the live skeleton has been looked for and not found. */
    private static final int MAX_SEARCHES = 8;
    private static final int SEARCH_EVERY = 10;
    private int searches;
    private int sinceSearch;

    /**
     * The smallest and largest value each of the twelve numbers of a slot
     * was ever seen holding, over every bone and every measured frame.
     */
    private final float[] slotLow = new float[12];
    private final float[] slotHigh = new float[12];
    private boolean slotRangeSeen;

    /** Where this model keeps its pose, once measured. */
    private enum Mode {
        REST_ROTATION,
        MATRIX,
        TRS
    }

    private Mode mode = Mode.REST_ROTATION;
    private boolean rowMajorMatrix = true;
    private boolean mirroredSideways;
    private int sidewaysStraight;
    private int sidewaysFlipped;

    private YsmSkeletonOverlay() {
    }

    /** Forgets everything and puts back what was there before. */
    public void reset() {
        this.restore();
        this.stage = Stage.IDLE;
        this.owner = null;
        this.subject = null;
        this.bones = Map.of();
        this.allBones = List.of();
        this.others = List.of();
        this.structural = List.of();
        this.everythingElse = List.of();
        this.lastSample = new float[0];
        this.moved = new boolean[0];
        this.frames = 0;
        this.movedNumbers = 0;
        this.slotRangeSeen = false;
        this.jumpsReported = 0;
        this.lastWritten.clear();
        this.lastEuler.clear();
        this.lastSolvedEuler.clear();
        this.solver = null;
        this.boneByName.clear();
        this.restByName.clear();
        this.mode = Mode.REST_ROTATION;
        this.mirroredSideways = false;
        this.sidewaysStraight = 0;
        this.sidewaysFlipped = 0;
        this.form = "";
        this.dormant = false;
        this.sinceFormCheck = 0;
        this.formBones = Map.of();
        this.parentBone = Map.of();
        java.util.Arrays.fill(this.movedInGroup, 0);
    }

    public boolean isActive() {
        return this.stage == Stage.ACTIVE;
    }

    public void setProbe(boolean enabled) {
        this.probe = enabled;
    }

    public boolean probe() {
        return this.probe;
    }

    /**
     * True while there is a reason to enter YSM's render through the bridge:
     * either to find the skeleton, to measure it, or to pose it.
     */
    public boolean wants(AbstractClientPlayer player, ResourceLocation texture) {
        if (this.stage == Stage.GIVEN_UP && player.getUUID().equals(this.owner) && sameTexture(texture, this.subject)) {
            return false;
        }

        return true;
    }

    /**
     * Called right before Yes Steve Model draws this player. Only finds the
     * skeleton; nothing is written here, because YSM has not posed it yet.
     */
    public void prepare(AbstractClientPlayer player, Object renderer, ResourceLocation texture) {
        try {
            boolean sameModel = player.getUUID().equals(this.owner) && sameTexture(texture, this.subject);

            if (sameModel && this.stage != Stage.IDLE) {
                return;
            }

            if (sameModel) {
                // Still looking for this very model. Not every frame: the
                // search walks a good part of YSM's object graph.
                if (++this.sinceSearch % SEARCH_EVERY != 0) {
                    return;
                }
            } else {
                this.searches = 0;
                this.sinceSearch = 0;
            }

            this.reset();
            this.owner = player.getUUID();
            this.subject = texture;
            this.discover(player, renderer, texture);
        } catch (Throwable t) {
            EpicYsm.LOGGER.warn("Skeleton overlay stopped while looking for the skeleton", t);
            this.stage = Stage.GIVEN_UP;
        }
    }

    /**
     * Called from inside YSM's own render, after its animation has posed the
     * skeleton and before it draws. This is the only moment a write survives.
     */
    public void onDraw(AbstractClientPlayer player, float partialTicks) {
        try {
            switch (this.stage) {
                case MEASURING -> this.measure();
                case ACTIVE -> this.writePose(player, partialTicks);
                default -> {
                }
            }
        } catch (Throwable t) {
            EpicYsm.LOGGER.warn("Skeleton overlay stopped while posing the skeleton", t);
            this.stage = Stage.GIVEN_UP;
        }
    }

    /* ------------------------------------------------------------------
     * 1. Finding the live skeleton
     * ------------------------------------------------------------------ */

    private void discover(AbstractClientPlayer player, Object renderer, ResourceLocation texture) {
        // Finding the live skeleton used to be done here, by walking out
        // from the texture Yes Steve Model was drawing with. It kept coming
        YsmLiveSkeleton.Skeleton model = YsmLiveSkeleton.readFor(player, renderer, texture);
        List<Object> live = model == null ? List.of() : model.objects();

        // The model's size may have just been read alongside; the skeleton
        // about to be built must use it from its first frame.
        YsmPoseSolver.setModelScale(YsmRenderBridge.rulerFor(texture));

        if (live.isEmpty()) {
            // Not there yet is not the same as not there: on the first
            // frames a player is drawn, YSM has not finished building the
            if (++this.searches < MAX_SEARCHES) {
                this.stage = Stage.IDLE;
                return;
            }

            com.argorice.epicysm.client.Diag.info("Skeleton overlay: no skeleton found for the texture Yes Steve Model draws this"
                    + " player with after {} tries; its own animations are left alone", this.searches);
            this.stage = Stage.GIVEN_UP;
            return;
        }

        this.searches = 0;

        List<Object> everyBoneShape = live;
        int nodes = live.size();
        int candidates = live.size();

        // --- which form is on screen ---
        //
        Map<String, Bone> byLiveName = new LinkedHashMap<>();
        Map<String, List<Bone>> formRoleBones = new LinkedHashMap<>();
        Map<String, Set<Role>> formRoles = new LinkedHashMap<>();
        List<Bone> everyBone = new ArrayList<>();

        for (Object value : live) {
            // Every bone by its own name - boneNameOf answers only for the
            // twenty-odd a body is made of, and reading the model through
            String name = YsmLiveSkeleton.nameOf(value);
            Field rotation = rotationFieldOf(value);

            if (name == null || name.isEmpty() || rotation == null) {
                continue;
            }

            float[] original = new float[3];

            try {
                if (rotation.get(value) instanceof Vector3f vec) {
                    original[0] = vec.x;
                    original[1] = vec.y;
                    original[2] = vec.z;
                }
            } catch (Throwable ignored) {
            }

            float[] matrices = sharedArray(value, 12);
            float[] quaternions = sharedArray(value, 4);
            int[] offsets = offsetsOf(value, matrices, quaternions);
            Bone bone = new Bone(name, value, rotation, original, offsets[0], offsets[1],
                    matrices, quaternions, pivotOf(value));
            everyBone.add(bone);
            byLiveName.putIfAbsent(name, bone);
            Role boneRole = ROLE_BY_NAME.get(baseName(name).toLowerCase(Locale.ROOT));

            if (boneRole != null) {
                String key = formKey(name);
                formRoleBones.computeIfAbsent(key, k -> new ArrayList<>()).add(bone);
                formRoles.computeIfAbsent(key, k -> java.util.EnumSet.noneOf(Role.class)).add(boneRole);
            }
        }

        Map<String, Bone> parents = new LinkedHashMap<>();

        if (model != null) {
            for (YsmLiveSkeleton.LiveBone bone : model.bones()) {
                Bone above = bone.parent() == null ? null : byLiveName.get(bone.parent());

                if (above != null) {
                    parents.put(bone.name(), above);
                }
            }
        }

        this.parentBone = parents;
        Map<String, List<Bone>> complete = new LinkedHashMap<>();

        for (Map.Entry<String, Set<Role>> entry : formRoles.entrySet()) {
            if (entry.getValue().size() >= MIN_BONES) {
                complete.put(entry.getKey(), formRoleBones.get(entry.getKey()));
            }
        }

        this.formBones = complete;
        String chosen = complete.containsKey("") ? "" : complete.isEmpty() ? "" : complete.keySet().iterator().next();
        boolean anyShown = false;

        for (String key : complete.keySet()) {
            if (this.formShown(key)) {
                anyShown = true;

                if (key.isEmpty() || !this.formShown(chosen)) {
                    chosen = key;
                }

                if (key.isEmpty()) {
                    break;
                }
            }
        }

        this.form = chosen;
        this.dormant = complete.size() > 0 && !anyShown;

        if (complete.size() > 1) {
            com.argorice.epicysm.client.Diag.info("Skeleton overlay: this model carries {} bodies ({}); the one on screen is '{}'"
                    + " and it is the one posed. The others are left alone, and the moment another one is"
                    + " shown the skeleton is read again for it.", complete.size(),
                    String.join(", ", complete.keySet().stream().map(k -> k.isEmpty() ? "plain" : k).toList()),
                    chosen.isEmpty() ? "plain" : chosen);
        }

        // A bone of another body, and everything hung under another body's
        // root. Only a root carries its mark down: a chest or an arm bone
        Set<String> foreign = new HashSet<>();

        for (Bone bone : everyBone) {
            String key = formKey(bone.name());

            if (!key.equals(chosen) && bodyName(baseName(bone.name()).toLowerCase(Locale.ROOT))) {
                foreign.add(bone.name());
            }
        }

        Set<String> foreignRoots = new HashSet<>();

        for (String name : foreign) {
            if (!parents.containsKey(name)) {
                foreignRoots.add(name);
            }
        }

        for (Bone bone : everyBone) {
            if (formKey(bone.name()).equals(chosen) && bodyName(baseName(bone.name()).toLowerCase(Locale.ROOT))) {
                continue;
            }

            Bone at = parents.get(bone.name());

            for (int step = 0; at != null && step < 40; step++) {
                // Inside the body being posed: whatever is above that is
                // shared, not another body's.
                if (formKey(at.name()).equals(chosen) && bodyName(baseName(at.name()).toLowerCase(Locale.ROOT))) {
                    break;
                }

                if (foreignRoots.contains(at.name())) {
                    foreign.add(bone.name());
                    break;
                }

                at = parents.get(at.name());
            }
        }

        // The model as the solver sees it: the form on screen under the
        // plain names it knows, the other forms gone.
        Map<String, String> solverName = new LinkedHashMap<>();

        for (Bone bone : everyBone) {
            if (foreign.contains(bone.name())) {
                continue;
            }

            String plain = chosen.isEmpty() ? bone.name() : baseName(bone.name());
            solverName.put(bone.name(), plain);
        }

        List<YsmLiveSkeleton.LiveBone> kept = new ArrayList<>();
        List<Object> keptLive = new ArrayList<>();

        if (model != null) {
            for (int i = 0; i < model.bones().size() && i < live.size(); i++) {
                YsmLiveSkeleton.LiveBone bone = model.bones().get(i);
                String plain = solverName.get(bone.name());

                if (plain == null) {
                    continue;
                }

                String above = bone.parent() == null ? null : solverName.get(bone.parent());
                kept.add(new YsmLiveSkeleton.LiveBone(plain, bone.pivotX(), bone.pivotY(), bone.pivotZ(), above));
                keptLive.add(live.get(i));
            }

            model = new YsmLiveSkeleton.Skeleton(kept, keptLive, model.owner(), model.repeats());
            live = keptLive;
        }

        Map<Role, List<Bone>> byRole = new EnumMap<>(Role.class);
        List<Bone> flat = new ArrayList<>();

        for (Bone found : everyBone) {
            String plain = solverName.get(found.name());

            if (plain == null) {
                continue;
            }

            Role boneRole = ROLE_BY_NAME.get(plain.toLowerCase(Locale.ROOT));

            if (boneRole == null) {
                continue;
            }

            Bone bone = plain.equals(found.name()) ? found
                    : new Bone(plain, found.target(), found.rotation(), found.original(), found.matrixAt(),
                            found.quatAt(), found.matrices(), found.quaternions(), found.pivot());
            byRole.computeIfAbsent(boneRole, key -> new ArrayList<>()).add(bone);
            flat.add(bone);
        }

        if (byRole.size() < MIN_BONES) {
            com.argorice.epicysm.client.Diag.info("Skeleton overlay: only {} humanoid bone(s) found among {} candidate(s);"
                    + " too little to pose safely, leaving Yes Steve Model alone", byRole.size(), candidates);
            this.stage = Stage.GIVEN_UP;
            return;
        }

        this.bones = byRole;
        this.allBones = flat;

        // Everything else of this body, with its own rest rotation - the
        // old sibling walk gave every one of them a rest of zero, so a
        // bone modelled at an angle was held straight.
        Set<Object> taken = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        for (Bone bone : flat) {
            taken.add(bone.target());
        }

        List<Bone> siblings = new ArrayList<>();

        for (Bone bone : everyBone) {
            if (taken.contains(bone.target()) || foreign.contains(bone.name()) || !bone.hasMatrix()) {
                continue;
            }

            String plain = solverName.getOrDefault(bone.name(), bone.name());
            siblings.add(plain.equals(bone.name()) ? bone
                    : new Bone(plain, bone.target(), bone.rotation(), bone.original(), bone.matrixAt(),
                            bone.quatAt(), bone.matrices(), bone.quaternions(), bone.pivot()));
        }

        this.others = List.copyOf(siblings);
        this.structural = structuralBones(model, live, byRole);
        this.everythingElse = notDriven(flat, this.structural, this.others);
        this.measureScale();
        this.boneByName.clear();
        this.restByName.clear();

        for (Bone bone : flat) {
            this.boneByName.putIfAbsent(bone.name(), bone);
            this.restByName.putIfAbsent(bone.name(), bone.original());
        }

        for (Bone bone : this.others) {
            this.boneByName.putIfAbsent(bone.name(), bone);
            this.restByName.putIfAbsent(bone.name(), bone.original());
        }

        for (Bone bone : this.structural) {
            this.boneByName.putIfAbsent(bone.name(), bone);
            this.restByName.putIfAbsent(bone.name(), bone.original());
        }

        this.findOwnWeapons(texture);
        this.solver = model == null ? null : new YsmPoseSolver(model, this.restByName);

        if (this.solver != null && this.solver.isReady()) {
            com.argorice.epicysm.client.Diag.info("Skeleton overlay: built the same skeleton the converted models use, out of this"
                    + " model's own pivots ({} joint(s) put back to a body's proportions) - bones are placed,"
                    + " not just turned", this.solver.repaired());
        } else {
            this.solver = null;
        }
        this.describeModel(texture, model, everyBone, foreign, solverName, byRole);
        this.lastSample = new float[0];
        this.moved = new boolean[flat.size()];
        this.frames = 0;
        this.movedNumbers = 0;
        this.stage = Stage.MEASURING;

        StringBuilder byName = new StringBuilder();

        for (Map.Entry<Role, List<Bone>> entry : byRole.entrySet()) {
            byName.append(byName.length() == 0 ? "" : ", ").append(entry.getKey()).append('=');

            for (int i = 0; i < entry.getValue().size(); i++) {
                byName.append(i > 0 ? "+" : "").append(entry.getValue().get(i).name());
            }
        }

        com.argorice.epicysm.client.Diag.info("Skeleton overlay: {} live bone(s) for texture {} ({} candidate(s) in {} node(s));"
                        + " checking whether Yes Steve Model animates them. Joints: {}",
                flat.size(), texture, candidates, nodes, byName);
    }

    private List<Object> seeds(AbstractClientPlayer player, Object renderer) {
        List<Object> seeds = new ArrayList<>();
        seeds.add(renderer);
        seeds.add(player);

        // The live model does not always hang off the renderer: YSM keeps it
        // in its own statics. Every YSM class in the jar is asked for them.
        java.nio.file.Path jar = ysmJarPath();

        if (jar == null) {
            return seeds;
        }

        ClassLoader loader = renderer.getClass().getClassLoader();
        Set<Class<?>> done = new HashSet<>();

        try (ZipFile zipFile = new ZipFile(jar.toFile())) {
            var entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (!name.endsWith(".class")) {
                    continue;
                }

                String className = name.substring(0, name.length() - 6).replace('/', '.');

                if (!className.startsWith(YSM_PACKAGE)) {
                    continue;
                }

                try {
                    Class<?> type = Class.forName(className, false, loader);

                    if (!done.add(type)) {
                        continue;
                    }

                    for (Field field : safeFields(type)) {
                        if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                            continue;
                        }

                        if (field.trySetAccessible()) {
                            seeds.add(field.get(null));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        return seeds;
    }

    /** Keeps the bones that belong to the model actually on screen. */
    private static List<Object> pickLiveSkeleton(List<Object> rawBones, List<Object> textureHolders,
                                                 Map<Object, Object> parents) {
        List<Object> best = rawBones;
        int bestRoles = -1;

        for (Object holder : textureHolders) {
            List<Object> group = skeletonUnder(rawBones, holder, parents);
            int roles = distinctRoles(group);

            if (roles > bestRoles || (roles == bestRoles && group.size() < best.size())) {
                bestRoles = roles;
                best = group;
            }
        }

        return best;
    }

    private static int distinctRoles(List<Object> bones) {
        Set<Role> roles = new HashSet<>();

        for (Object bone : bones) {
            String name = boneNameOf(bone);

            if (name != null) {
                Role boneRole = ROLE_BY_NAME.get(name.toLowerCase(Locale.ROOT));

                if (boneRole != null) {
                    roles.add(boneRole);
                }
            }
        }

        return roles.size();
    }

    private static List<Object> skeletonUnder(List<Object> rawBones, Object textureHolder,
                                              Map<Object, Object> parents) {
        List<Object> anchorChain = new ArrayList<>();

        for (Object node = textureHolder; node != null; node = parents.get(node)) {
            anchorChain.add(node);

            if (anchorChain.size() > 64) {
                break;
            }
        }

        Map<Object, Integer> rank = new IdentityHashMap<>();

        for (int i = 0; i < anchorChain.size(); i++) {
            rank.putIfAbsent(anchorChain.get(i), i);
        }

        // For every bone: how far up does one have to go to meet the texture?
        Map<Integer, List<Object>> byAncestor = new LinkedHashMap<>();

        for (Object bone : rawBones) {
            Object node = bone;

            for (int hops = 0; node != null && hops < 64; hops++) {
                Integer level = rank.get(node);

                if (level != null) {
                    byAncestor.computeIfAbsent(level, key -> new ArrayList<>()).add(bone);
                    break;
                }

                node = parents.get(node);
            }
        }

        // Widening from the texture outwards: the first owner that holds a
        // whole skeleton is the model being drawn. A bone met at level L is
        // also under every level above it, so the groups accumulate.
        List<Integer> levels = new ArrayList<>(byAncestor.keySet());
        levels.sort(null);
        List<Object> group = new ArrayList<>();

        for (int level : levels) {
            group.addAll(byAncestor.get(level));

            if (distinctRoles(group) >= MIN_BONES) {
                return group;
            }
        }

        return rawBones;
    }

    private static boolean holdsTexture(Object value, ResourceLocation texture) {
        if (texture == null) {
            return false;
        }

        for (Class<?> type = value.getClass(); type != null && isYsmClass(type); type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }

                try {
                    if (field.trySetAccessible() && field.get(value) instanceof ResourceLocation location
                            && texture.equals(location)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return false;
    }

    /** The bone's name, if this object carries one this mod knows. */
    @Nullable
    private static String boneNameOf(Object value) {
        for (Class<?> type = value.getClass(); type != null && isYsmClass(type); type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                    continue;
                }

                try {
                    if (field.trySetAccessible() && field.get(value) instanceof String string
                            && ROLE_BY_NAME.containsKey(string.toLowerCase(Locale.ROOT))) {
                        return string;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return null;
    }

    /**
     * The bone's rotation. A YSM bone keeps its pivot in loose floats and its
     * rotation in a single Vector3f, so the Vector3f is unambiguous.
     */
    @Nullable
    private static Field rotationFieldOf(Object value) {
        Field found = null;

        for (Class<?> type = value.getClass(); type != null && isYsmClass(type); type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != Vector3f.class) {
                    continue;
                }

                if (!field.trySetAccessible()) {
                    continue;
                }

                try {
                    if (!(field.get(value) instanceof Vector3f)) {
                        continue;
                    }
                } catch (Throwable ignored) {
                    continue;
                }

                if (found != null) {
                    // More than one: the shape is not the one measured, and
                    // guessing which vector is the rotation would twist the
                    // model. Better to skip this object entirely.
                    return null;
                }

                found = field;
            }
        }

        return found;
    }

    /**
     * The rest of this model's bones: the ones sharing the very same array.
     * Two models loaded at once each have their own, so the array itself
     * says which bones belong together - no guessing needed.
     */
    private static List<Bone> collectSiblings(List<Object> everyBoneShape, List<Bone> known) {
        if (known.isEmpty()) {
            return List.of();
        }

        float[] shared = null;

        for (Bone bone : known) {
            if (bone.matrices() != null) {
                shared = bone.matrices();
                break;
            }
        }

        if (shared == null) {
            return List.of();
        }

        Set<Object> alreadyKnown = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        for (Bone bone : known) {
            alreadyKnown.add(bone.target());
        }

        List<Bone> siblings = new ArrayList<>();

        for (Object value : everyBoneShape) {
            if (alreadyKnown.contains(value) || sharedArray(value, 12) != shared) {
                continue;
            }

            Field rotation = rotationFieldOf(value);

            if (rotation == null) {
                continue;
            }

            // Its own name, not only the twenty-odd a body is made of.
            // boneNameOf answers for those and nothing else, which is what
            String name = YsmLiveSkeleton.nameOf(value);
            float[] quaternions = sharedArray(value, 4);
            int[] offsets = offsetsOf(value, shared, quaternions);
            siblings.add(new Bone(name == null ? "" : name, value, rotation, new float[3], offsets[0], offsets[1],
                    shared, quaternions, pivotOf(value)));
        }

        return siblings;
    }

    /** The bone's three pivot numbers, in declaration order. */
    private static float[] pivotOf(Object value) {
        float[] pivot = new float[3];
        int found = 0;

        for (Class<?> type = value.getClass(); type != null && isYsmClass(type) && found < 3; type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != float.class || !field.trySetAccessible()) {
                    continue;
                }

                try {
                    if (found < 3) {
                        pivot[found++] = field.getFloat(value);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return pivot;
    }

    /**
     * Where this bone's numbers start in the two arrays the whole model
     * shares - and they are offsets, not an index.
     */
    private static int[] offsetsOf(Object value, @Nullable float[] matrices, @Nullable float[] quaternions) {
        List<Integer> ints = new ArrayList<>();

        for (Class<?> type = value.getClass(); type != null && isYsmClass(type); type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != int.class || !field.trySetAccessible()) {
                    continue;
                }

                try {
                    ints.add(field.getInt(value));
                } catch (Throwable ignored) {
                }
            }
        }

        for (int matrixAt : ints) {
            if (matrixAt < 0 || matrixAt % 12 != 0 || matrices == null || matrixAt + 12 > matrices.length) {
                continue;
            }

            for (int quatAt : ints) {
                if (quatAt < 0 || quatAt % 4 != 0 || quaternions == null || quatAt + 4 > quaternions.length) {
                    continue;
                }

                if (matrixAt / 12 == quatAt / 4) {
                    return new int[] { matrixAt, quatAt };
                }
            }
        }

        // No pair agrees: fall back to the old reading, an index into both.
        for (int index : ints) {
            if (index >= 0 && index < 8192 && matrices != null && (index + 1) * 12 <= matrices.length) {
                return new int[] { index * 12, index * 4 };
            }
        }

        return new int[] { -1, -1 };
    }

    /**
     * The float array the whole model shares, picked by how many numbers it
     * holds per slot: twelve for a matrix, four for a quaternion.
     */
    @Nullable
    private static float[] sharedArray(Object value, int perSlot) {
        for (Class<?> type = value.getClass(); type != null && isYsmClass(type); type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != float[].class || !field.trySetAccessible()) {
                    continue;
                }

                try {
                    if (field.get(value) instanceof float[] array && array.length > 0 && array.length % perSlot == 0
                            && array.length / perSlot > 16) {
                        // Twelve-per-slot and four-per-slot arrays differ in
                        // length by a factor of three, so the larger one is
                        // the matrices and the smaller the quaternions.
                        boolean wantsLarger = perSlot == 12;
                        float[] other = otherFloatArray(value, array);

                        if (other == null || (array.length > other.length) == wantsLarger) {
                            return array;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return null;
    }

    @Nullable
    private static float[] otherFloatArray(Object value, float[] exclude) {
        for (Class<?> type = value.getClass(); type != null && isYsmClass(type); type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != float[].class || !field.trySetAccessible()) {
                    continue;
                }

                try {
                    if (field.get(value) instanceof float[] array && array != exclude && array.length > 0) {
                        return array;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return null;
    }

    /* ------------------------------------------------------------------
     * 2. Is this skeleton actually animated?
     * ------------------------------------------------------------------ */

    /**
     * Watches the three places a pose could live and lets the model itself
     * say which one it uses: the rest rotation on the bone, the twelve
     * numbers per slot in the array the whole model shares, or the four
     * next to them. Whichever changes while the model is being drawn is the
     */
    private void measure() {
        float[] sample = this.sampleAll();
        this.noteSlotRange();

        if (this.frames > 0 && sample.length == this.lastSample.length) {
            for (int i = 0; i < sample.length; i++) {
                if (Math.abs(sample[i] - this.lastSample[i]) > 1.0E-5F) {
                    this.movedNumbers++;
                    int bone = i / NUMBERS_PER_BONE;
                    int group = (i % NUMBERS_PER_BONE) < 3 ? 0 : ((i % NUMBERS_PER_BONE) < 15 ? 1 : 2);
                    this.moved[bone] = true;
                    this.movedInGroup[group]++;
                }
            }
        }

        this.lastSample = sample;
        this.frames++;

        if (this.frames < LIVENESS_FRAMES) {
            return;
        }

        com.argorice.epicysm.client.Diag.info("Skeleton overlay: while the model was drawn, {} rest-rotation number(s),"
                        + " {} matrix number(s) and {} quaternion number(s) changed",
                this.movedInGroup[0], this.movedInGroup[1], this.movedInGroup[2]);
        this.reportSlotRange();

        // The parts reading is settled by the shape of the numbers, not by
        // catching them in motion: a model standing perfectly still still
        // says where its rotation lives.
        if (this.adoptTrs()) {
            return;
        }

        if (this.movedInGroup[1] > 0 && this.adoptMatrices()) {
            return;
        }

        if (this.movedInGroup[0] > 0) {
            this.keepOnlyTheLivingOnes();
            this.mode = Mode.REST_ROTATION;
            this.stage = Stage.ACTIVE;
            com.argorice.epicysm.client.Diag.info("Skeleton overlay ACTIVE on the rest rotations: {} bone(s)", this.allBones.size());
            return;
        }

        com.argorice.epicysm.client.Diag.info("Skeleton overlay: nothing Yes Steve Model exposes for this model changes while it is"
                + " drawn, so its pose is computed somewhere this mod cannot reach. Its own animations are kept.");
        this.stage = Stage.GIVEN_UP;
    }

    private static final int NUMBERS_PER_BONE = 19;

    /** Widens the seen range of every one of the twelve numbers. */
    private void noteSlotRange() {
        for (Bone bone : this.allBones) {
            if (!bone.hasMatrix()) {
                continue;
            }

            float[] matrices = bone.matrices();
            int at = bone.matrixAt();

            if (at < 0 || at + 12 > matrices.length) {
                continue;
            }

            for (int i = 0; i < 12; i++) {
                float value = matrices[at + i];

                if (!Float.isFinite(value)) {
                    continue;
                }

                if (!this.slotRangeSeen) {
                    this.slotLow[i] = value;
                    this.slotHigh[i] = value;
                } else {
                    this.slotLow[i] = Math.min(this.slotLow[i], value);
                    this.slotHigh[i] = Math.max(this.slotHigh[i], value);
                }
            }

            this.slotRangeSeen = true;
        }
    }

    /** Writes the measured range out, one entry per number of a slot. */
    private void reportSlotRange() {
        if (!this.slotRangeSeen) {
            return;
        }

        StringBuilder text = new StringBuilder();

        for (int i = 0; i < 12; i++) {
            text.append(i > 0 ? ", " : "").append('#').append(i).append(' ')
                    .append(Math.round(this.slotLow[i] * 1000.0F) / 1000.0F).append("..")
                    .append(Math.round(this.slotHigh[i] * 1000.0F) / 1000.0F);
        }

        com.argorice.epicysm.client.Diag.info("Skeleton overlay: the twelve numbers of a slot ranged over [{}]", text);
    }

    /** Rest rotation (3), matrix slot (12) and quaternion slot (4) per bone. */
    private float[] sampleAll() {
        float[] sample = new float[this.allBones.size() * NUMBERS_PER_BONE];

        for (int i = 0; i < this.allBones.size(); i++) {
            Bone bone = this.allBones.get(i);
            int base = i * NUMBERS_PER_BONE;
            Vector3f vec = bone.vector();

            if (vec != null) {
                sample[base] = vec.x;
                sample[base + 1] = vec.y;
                sample[base + 2] = vec.z;
            }

            if (bone.hasMatrix()) {
                System.arraycopy(bone.matrices(), bone.matrixAt(), sample, base + 3, 12);
            }

            if (bone.hasQuaternion()) {
                System.arraycopy(bone.quaternions(), bone.quatAt(), sample, base + 15, 4);
            }
        }

        return sample;
    }

    /**
     * Tests the reading that actually fits what Yes Steve Model writes: the
     * twelve numbers are not a matrix at all but a transform in parts -
     * three for the rotation, three for the position, three for the scale,
     * three for the pivot.
     */
    private boolean adoptTrs() {
        int fits = 0;
        int checked = 0;
        int pivotBones = 0;
        int pivotAtNine = 0;
        int pivotAtThree = 0;

        for (Bone bone : this.allBones) {
            if (!bone.hasMatrix()) {
                continue;
            }

            checked++;
            float[] m = bone.matrices();
            int at = bone.matrixAt();

            if (near(m[at + 6], 1.0F) && near(m[at + 7], 1.0F) && near(m[at + 8], 1.0F)) {
                fits++;
            }

            // Which three of the twelve carry the pivot is worth knowing:
            // it names the remaining part of the layout, and the bones far
            // from the middle of the model are the ones that can tell.
            float[] pivot = bone.pivot();

            if (Math.abs(pivot[0]) + Math.abs(pivot[1]) + Math.abs(pivot[2]) > 4.0F) {
                pivotBones++;

                if (samePlace(m, at + 9, pivot)) {
                    pivotAtNine++;
                } else if (samePlace(m, at + 3, pivot)) {
                    pivotAtThree++;
                }
            }
        }

        com.argorice.epicysm.client.Diag.info("Skeleton overlay: {} of {} bone(s) hold a scale of one where a transform in parts"
                + " would keep it; of {} bone(s) with a pivot away from the middle, {} repeat it at #9 and {}"
                + " at #3.{}", fits, checked, pivotBones, pivotAtNine, pivotAtThree, this.exampleSlot());

        if (checked < 4 || fits * 10 < checked * 7) {
            return false;
        }

        this.mode = Mode.TRS;
        this.stage = Stage.ACTIVE;
        com.argorice.epicysm.client.Diag.info("Skeleton overlay ACTIVE on the live rotations: {} body bone(s), written in radians"
                + " beside the position and the scale Yes Steve Model computed. Epic Fight now poses this model.",
                this.allBones.size());
        return true;
    }

    /**
     * Which way round the three rotation numbers run, and which side is
     * which, bit per axis plus one for the sides.
     */
    private static int axes;

    /** Which order the three numbers are read back in. */
    /**
     * Three angles describe a turn only once the order they are applied in
     * is known, and there are six of them. Nothing in memory says which one
     * Yes Steve Model uses, and the wrong choice agrees with the right one
     * on small angles and parts company by half a turn on a full swing -
     */
    public enum Order {
        ZYX,
        XYZ,
        YXZ,
        ZXY,
        YZX,
        XZY
    }

    private static Order order = Order.ZYX;

    /** What to do with the root bone. */
    public enum RootMode {
        EPIC_FIGHT,
        REST,
        YSM
    }

    private static RootMode root = RootMode.EPIC_FIGHT;

    /**
     * Whether to keep the three angles continuous between frames -
     * the settings. Switchable so that a change for the worse
     * can be pinned on one thing rather than two.
     */
    private static boolean smooth = true;

    /**
     * Whether to put Epic Fight's pose through its own rest orientations
     * before writing it.
     */
    private static boolean retarget = true;

    /**
     * How much of Yes Steve Model's own animation is switched off -
     * the settings.
     */
    public enum Hold {
        NONE,
        PATH,
        ALL
    }

    /**
     * ALL, as the converted models have it: there, hair and skirts and
     * props are skinned to the nearest joint and move with the body and
     * nothing else. PATH left everything Yes Steve Model animates but the
     * body itself to Yes Steve Model, and where a bone this mod knew
     */
    private static Hold hold = Hold.ALL;

    /** Whether the body is carried as well as turned. */
    private static boolean shift;

    public static boolean shift() {
        return shift;
    }

    public static void setShift(boolean value) {
        shift = value;
    }

    public static Hold hold() {
        return hold;
    }

    public static void setHold(Hold value) {
        hold = value;
    }

    public static boolean retarget() {
        return retarget;
    }

    public static void setRetarget(boolean value) {
        retarget = value;
    }

    public static boolean smooth() {
        return smooth;
    }

    public static void setSmooth(boolean value) {
        smooth = value;
    }

    public static RootMode root() {
        return root;
    }

    public static void setRoot(RootMode value) {
        root = value;
    }

    public static Order order() {
        return order;
    }

    public static void setOrder(Order value) {
        order = value;
    }

    /**
     * The three angles that rebuild this turn when applied in that order.
     *
     * Every one of the six is an exact inverse of its own order, so writing
     * in the right one is exact even for a bone pointing straight up; the
     * damage comes only from writing in the wrong one.
     */
    private static int asinIndex(Order which) {
        return switch (which) {
            case ZYX, XYZ -> 1;
            case YXZ, ZXY -> 0;
            case YZX, XZY -> 2;
        };
    }

    private static void toEuler(Quaternionf rotation, Order which, Vector3f out) {
        // The nine numbers of the turn, written out from the quaternion
        // here rather than borrowed from a matrix class. Which way round a
        Quaternionf q = new Quaternionf(rotation).normalize();
        float xx = q.x * q.x;
        float yy = q.y * q.y;
        float zz = q.z * q.z;
        float xy = q.x * q.y;
        float xz = q.x * q.z;
        float yz = q.y * q.z;
        float wx = q.w * q.x;
        float wy = q.w * q.y;
        float wz = q.w * q.z;
        float r00 = 1.0F - 2.0F * (yy + zz);
        float r01 = 2.0F * (xy - wz);
        float r02 = 2.0F * (xz + wy);
        float r10 = 2.0F * (xy + wz);
        float r11 = 1.0F - 2.0F * (xx + zz);
        float r12 = 2.0F * (yz - wx);
        float r20 = 2.0F * (xz - wy);
        float r21 = 2.0F * (yz + wx);
        float r22 = 1.0F - 2.0F * (xx + yy);

        float[] m = { r00, r01, r02, r10, r11, r12, r20, r21, r22 };
        float middle = switch (which) {
            case ZYX -> -r20;
            case XYZ -> r02;
            case YXZ -> -r12;
            case ZXY -> r21;
            case YZX -> r10;
            case XZY -> -r01;
        };

        // Straight up or straight down, the two turns either side of the
        // middle one are the same turn, and reading them separately reads
        if (Math.abs(middle) > 0.99999F) {
            String axes = which.name();
            float[] undo = about(axes.charAt(1), -asin(middle));
            float outer = angleAbout(axes.charAt(0), times(m, undo));
            out.set(0.0F, 0.0F, 0.0F);
            set(out, axes.charAt(0), outer);
            set(out, axes.charAt(1), asin(middle));
            return;
        }

        switch (which) {
            case ZYX -> out.set((float) Math.atan2(r21, r22), asin(-r20), (float) Math.atan2(r10, r00));
            case XYZ -> out.set((float) Math.atan2(-r12, r22), asin(r02), (float) Math.atan2(-r01, r00));
            case YXZ -> out.set(asin(-r12), (float) Math.atan2(r02, r22), (float) Math.atan2(r10, r11));
            case ZXY -> out.set(asin(r21), (float) Math.atan2(-r20, r22), (float) Math.atan2(-r01, r11));
            case YZX -> out.set((float) Math.atan2(-r12, r11), (float) Math.atan2(-r20, r00), asin(r10));
            case XZY -> out.set((float) Math.atan2(r21, r11), (float) Math.atan2(r02, r00), asin(-r01));
        }
    }

    private static void set(Vector3f out, char axis, float value) {
        switch (axis) {
            case 'X' -> out.x = value;
            case 'Y' -> out.y = value;
            default -> out.z = value;
        }
    }

    /** A turn of this much about one axis, as nine numbers, row by row. */
    private static float[] about(char axis, float angle) {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);

        return switch (axis) {
            case 'X' -> new float[] { 1, 0, 0, 0, c, -s, 0, s, c };
            case 'Y' -> new float[] { c, 0, s, 0, 1, 0, -s, 0, c };
            default -> new float[] { c, -s, 0, s, c, 0, 0, 0, 1 };
        };
    }

    private static float[] times(float[] a, float[] b) {
        float[] out = new float[9];

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float sum = 0.0F;

                for (int k = 0; k < 3; k++) {
                    sum += a[row * 3 + k] * b[k * 3 + col];
                }

                out[row * 3 + col] = sum;
            }
        }

        return out;
    }

    /** How far a turn about this one axis has gone. */
    private static float angleAbout(char axis, float[] m) {
        return (float) switch (axis) {
            case 'X' -> Math.atan2(m[7], m[4]);
            case 'Y' -> Math.atan2(m[2], m[0]);
            default -> Math.atan2(m[3], m[0]);
        };
    }

    /** The rotation these three angles make when applied in that order. */
    private static Quaternionf fromEuler(Order which, float x, float y, float z) {
        Quaternionf out = new Quaternionf();

        for (char axis : which.name().toCharArray()) {
            switch (axis) {
                case 'X' -> out.rotateX(x);
                case 'Y' -> out.rotateY(y);
                default -> out.rotateZ(z);
            }
        }

        return out;
    }

    private static float asin(float value) {
        return (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, value)));
    }

    /** The same turn, written so that it does not leap between frames. */
    private void continuousEuler(Quaternionf rotation, Order which, @Nullable float[] previous, Vector3f out) {
        toEuler(rotation, which, out);

        if (previous == null || !smooth) {
            return;
        }

        int middle = asinIndex(which);
        float[] base = { out.x, out.y, out.z };
        float[] other = new float[3];

        for (int i = 0; i < 3; i++) {
            other[i] = i == middle
                    ? (float) (Math.PI * Math.signum(base[i] == 0.0F ? 1.0F : base[i])) - base[i]
                    : base[i] + (float) Math.PI;
        }

        nearest(base, previous);
        nearest(other, previous);

        // With a plain "whichever is nearer", two spellings that sit about
        // equally far apart swap places every frame: the log had the root
        float[] best = distance(other, previous) + 0.5F < distance(base, previous) ? other : base;
        out.set(best[0], best[1], best[2]);
    }

    /** Walks each angle to the revolution closest to the previous frame. */
    private static void nearest(float[] angles, float[] previous) {
        for (int i = 0; i < 3; i++) {
            angles[i] += (float) (Math.PI * 2.0 * Math.round((previous[i] - angles[i]) / (Math.PI * 2.0)));
        }
    }

    private static float distance(float[] angles, float[] previous) {
        return Math.abs(angles[0] - previous[0]) + Math.abs(angles[1] - previous[1]) + Math.abs(angles[2] - previous[2]);
    }

    public static int axes() {
        return axes;
    }

    public static void setAxes(int value) {
        axes = value & 15;
    }

    /** The same role on the other side of the body. */
    private static Role mirrored(Role role) {
        return switch (role) {
            case ARM_L -> Role.ARM_R;
            case ARM_R -> Role.ARM_L;
            case FOREARM_L -> Role.FOREARM_R;
            case FOREARM_R -> Role.FOREARM_L;
            case HAND_L -> Role.HAND_R;
            case HAND_R -> Role.HAND_L;
            case THIGH_L -> Role.THIGH_R;
            case THIGH_R -> Role.THIGH_L;
            case SHIN_L -> Role.SHIN_R;
            case SHIN_R -> Role.SHIN_L;
            case FOOT_L -> Role.FOOT_R;
            case FOOT_R -> Role.FOOT_L;
            default -> role;
        };
    }

    private static boolean near(float value, float target) {
        return Math.abs(value - target) < 0.02F;
    }

    /** Whether three numbers at `at` repeat the bone's pivot, either sign. */
    private static boolean samePlace(float[] numbers, int at, float[] pivot) {
        if (at + 3 > numbers.length) {
            return false;
        }

        for (int i = 0; i < 3; i++) {
            float value = numbers[at + i];

            if (Math.abs(value - pivot[i]) > 0.01F && Math.abs(value + pivot[i]) > 0.01F) {
                return false;
            }
        }

        return true;
    }

    /** Epic Fight's local rotations into the rotation part of each slot. */
    /**
     * Puts every driven bone exactly where the solver says, turn and place
     * both. This is the path that reproduces the converted models.
     */
    private boolean writeSolved(AbstractClientPlayer player, float partialTicks) {
        YsmPoseSolver ready = this.solver;

        if (ready == null) {
            return false;
        }

        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(player, LivingEntityPatch.class);

        if (patch == null || patch.getAnimator() == null) {
            return false;
        }

        Pose pose = patch.getAnimator().getPose(partialTicks);

        if (pose == null) {
            return false;
        }

        Map<String, YsmPoseSolver.Placement> placements = ready.solve(pose, this.restByName);

        if (placements.isEmpty()) {
            return false;
        }

        Vector3f euler = new Vector3f();

        for (Bone bone : this.heldBones()) {
            if (!placements.containsKey(bone.name())) {
                float[] rest = bone.original();
                float[] m = bone.matrices();
                int at = bone.matrixAt();
                m[at] = rest[0];
                m[at + 1] = rest[1];
                m[at + 2] = rest[2];
                clearOffset(m, at);
            }
        }

        for (Map.Entry<String, YsmPoseSolver.Placement> entry : placements.entrySet()) {
            Bone bone = this.boneByName.get(entry.getKey());

            if (bone == null || !bone.hasMatrix()) {
                continue;
            }

            YsmPoseSolver.Placement placement = entry.getValue();
            // Kept apart from any other picture of this player.
            //
            boolean live = YsmPoseSolver.worldFrame();
            this.continuousEuler(placement.rotation(), order,
                    live ? this.lastSolvedEuler.get(entry.getKey()) : null, euler);

            if (live) {
                this.lastSolvedEuler.put(entry.getKey(), new float[] { euler.x, euler.y, euler.z });
            }

            // Written the way Yes Steve Model reads them: it composes
            // rotateZYX(third, second, first) with no sign changed, so the
            // three angles go in as they come out.
            float[] m = bone.matrices();
            int at = bone.matrixAt();
            m[at] = euler.x;
            m[at + 1] = euler.y;
            m[at + 2] = euler.z;
            // The sideways one goes in turned round. Read out of Yes Steve
            // Model's bytecode: it applies the three position numbers as
            Vector3f offset = placement.offset();
            m[at + 3] = -offset.x * placeScale;
            m[at + 4] = offset.y * placeScale;
            m[at + 5] = offset.z * placeScale;
        }

        return true;
    }

    /** Continuity is kept per bone name here, not per role. */
    private final Map<String, float[]> lastSolvedEuler = new java.util.HashMap<>();

    /** The model's own weapons: put away while Epic Fight is posing it. */
    private List<Bone> ownWeapons = List.of();

    /** The bones Yes Steve Model hangs the player's own item on. */
    private List<Bone> handLocators = List.of();

    /** Whether this model has a locator to collapse, so ours can take over. */
    public boolean canDrawOwnItem() {
        return !this.handLocators.isEmpty();
    }

    /**
     * Epic Fight's joints where the model was drawn, for the item in hand.
     *
     * Empty unless this very frame was posed by Epic Fight: out of battle
     * Yes Steve Model draws the item itself, and its locator was not
     * collapsed, so anything drawn here would be a second copy.
     */
    public Map<String, Matrix4f> drawnJoints(AbstractClientPlayer player) {
        YsmPoseSolver ready = this.solver;

        if (ready == null || this.probe || this.dormant || this.stage != Stage.ACTIVE || this.mode != Mode.TRS
                || !this.canDrawOwnItem() || !epicFightInCharge(player)) {
            return Map.of();
        }

        return ready.posedJointsAsDrawn();
    }

    /** Where the tool joint actually is, for the log - not how far it moved. */
    @Nullable
    public Matrix4f drawnToolJoint() {
        YsmPoseSolver ready = this.solver;
        return ready == null ? null : ready.posedJointsAsDrawn().get("Tool_R");
    }

    /** The model's own hand, as this mod has just posed it. */
    @Nullable
    public Matrix4f drawnHand() {
        YsmPoseSolver ready = this.solver;
        List<Bone> found = this.bones.get(Role.HAND_R);

        if (ready == null || found == null || found.isEmpty()) {
            return null;
        }

        String hand = found.get(0).name();

        // Better still, where the model's author said a weapon goes.
        //
        for (Bone bone : this.handLocators) {
            if (bone.name().toLowerCase(Locale.ROOT).startsWith("right")) {
                Matrix4f carried = ready.carriedAsDrawn(hand, bone.name());

                if (carried != null) {
                    return carried;
                }
            }
        }

        return ready.boneAsDrawn(hand);
    }

    /** How tall this model is in its own units, for a sanity check in the log. */
    public float modelHeight() {
        YsmPoseSolver ready = this.solver;
        return ready == null ? 0.0F : ready.height();
    }

    /** How many blocks one of this model's units is worth. */
    public float blocksPerUnit() {
        return this.pixelsPerBlock > 0.0F ? 16.0F / this.pixelsPerBlock : 0.0F;
    }

    /** The model's own bone that Yes Steve Model hangs the item on. */
    @Nullable
    public Matrix4f drawnHandBone() {
        YsmPoseSolver ready = this.solver;

        if (ready == null || this.handLocators.isEmpty()) {
            return null;
        }

        for (Bone bone : this.handLocators) {
            String name = bone.name().toLowerCase(Locale.ROOT);

            if (name.startsWith("right")) {
                return ready.boneAsDrawn(bone.name());
            }
        }

        return ready.boneAsDrawn(this.handLocators.get(0).name());
    }

    /**
     * Writes one file per model describing every bone as this mod sees it:
     * form, part of the body, joint, the bone above, where it rests, whether
     * it is driven, held, left alone or belongs to another form, and
     * whether it is drawn right now. config/epicysm/bones/<texture>.txt;
     */
    private void describeModel(@Nullable ResourceLocation texture, @Nullable YsmLiveSkeleton.Skeleton model,
                               List<Bone> everyBone, Set<String> foreign, Map<String, String> solverName,
                               Map<Role, List<Bone>> byRole) {
        try {
            String id = texture == null ? "unknown" : texture.getPath().replaceAll("[^A-Za-z0-9_.-]", "_");
            java.nio.file.Path file = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config/epicysm/bones").resolve(id + ".txt");
            java.nio.file.Files.createDirectories(file.getParent());
            Map<String, String> parentOf = new LinkedHashMap<>();
            Map<String, String> joints = this.solver == null ? Map.of() : this.solver.boneJoints();

            if (model != null) {
                for (YsmLiveSkeleton.LiveBone bone : model.bones()) {
                    if (bone.parent() != null) {
                        parentOf.put(bone.name(), bone.parent());
                    }
                }
            }

            Set<String> driven = new HashSet<>();

            for (List<Bone> list : byRole.values()) {
                for (Bone bone : list) {
                    driven.add(bone.name());
                }
            }

            Set<String> held = new HashSet<>();

            for (Bone bone : this.heldBones()) {
                held.add(bone.name());
            }

            List<String> out = new ArrayList<>();
            out.add("Model " + texture + " as EpicYSM reads it. Form posed: '" + (this.form.isEmpty() ? "plain" : this.form)
                    + "'" + (this.dormant ? " (not on screen right now)" : ""));
            out.add("name | form | as solver | role | joint | above | status | shown | pivot | rest rotation (rad)");
            List<String> rows = new ArrayList<>();

            for (Bone bone : everyBone) {
                String plain = solverName.get(bone.name());
                String status = foreign.contains(bone.name()) ? "other form"
                        : plain != null && driven.contains(plain) ? "driven"
                        : plain != null && held.contains(plain) ? "held still"
                        : plain != null && joints.containsKey(plain.toLowerCase(Locale.ROOT)) ? "carried by joint"
                        : "left to YSM";
                String role = plain == null ? "" : String.valueOf(ROLE_BY_NAME.get(plain.toLowerCase(Locale.ROOT)));
                String joint = plain == null ? "" : joints.getOrDefault(plain.toLowerCase(Locale.ROOT), "");
                float[] pivot = bone.pivot();
                float[] rest = bone.original();
                rows.add(String.format(Locale.ROOT, "%s | %s | %s | %s | %s | %s | %s | %s | %.3f %.3f %.3f | %.3f %.3f %.3f",
                        bone.name(), formKey(bone.name()).isEmpty() ? "plain" : formKey(bone.name()),
                        plain == null ? "-" : plain, "null".equals(role) ? "" : role, joint,
                        parentOf.getOrDefault(plain == null ? bone.name() : plain, "?"), status,
                        this.shown(bone) ? "yes" : "no",
                        pivot == null ? 0.0F : pivot[0], pivot == null ? 0.0F : pivot[1], pivot == null ? 0.0F : pivot[2],
                        rest == null ? 0.0F : rest[0], rest == null ? 0.0F : rest[1], rest == null ? 0.0F : rest[2]));
            }

            rows.sort(String::compareTo);
            out.addAll(rows);
            java.nio.file.Files.write(file, out, java.nio.charset.StandardCharsets.UTF_8);
            com.argorice.epicysm.client.Diag.info("Wrote how this mod reads the model's {} bone(s) to {}", everyBone.size(), file);
        } catch (Throwable t) {
            EpicYsm.LOGGER.debug("Could not write the model description", t);
        }
    }

    /**
     * Picks out the model's own weapons, and writes down every bone it has.
     *
     * The names are all there is to go on - an encrypted model hands over no
     * geometry and no hierarchy - so the list is written to a file as well,
     * to be read and added to.
     */
    private void findOwnWeapons(@Nullable ResourceLocation texture) {
        List<Bone> weapons = new ArrayList<>();
        List<Bone> locators = new ArrayList<>();
        List<String> described = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (Bone bone : this.boneByName.values()) {
            String name = bone.name();

            if (name == null || name.isEmpty() || !seen.add(name)) {
                continue;
            }

            boolean weapon = WeaponBones.isWeapon(name);

            if (weapon && bone.hasMatrix()) {
                weapons.add(bone);
            }

            if (bone.hasMatrix() && WeaponBones.isHandLocator(name)) {
                locators.add(bone);
            }

            float[] pivot = bone.pivot();
            described.add(String.format(Locale.ROOT, "%-32s pivot %8.3f %8.3f %8.3f%s", name,
                    pivot == null ? 0.0F : pivot[0], pivot == null ? 0.0F : pivot[1],
                    pivot == null ? 0.0F : pivot[2], weapon ? "   <- put away in battle" : ""));
        }

        described.sort(String::compareTo);
        this.ownWeapons = weapons;
        this.handLocators = locators;

        // The hand locator is held still along with the rest.
        //
        if (!locators.isEmpty()) {
            List<Bone> held = new ArrayList<>(this.structural);

            for (Bone bone : locators) {
                if (held.stream().noneMatch(other -> other.name().equals(bone.name()))) {
                    held.add(bone);
                }
            }

            this.structural = List.copyOf(held);
        }
        if (com.argorice.epicysm.client.Diag.on()) {
            WeaponBones.describe(texture == null ? null : texture.toString(), described);
        }

        if (!locators.isEmpty()) {
            List<String> names = new ArrayList<>();

            for (Bone bone : locators) {
                names.add(bone.name());
            }

            com.argorice.epicysm.client.Diag.info("Items: this model hangs the held item on {}, so in battle Epic Fight's own"
                    + " look for that item is drawn there instead of the plain inventory one."
                    + " The settings screen can give it back to Yes Steve Model", names);
        } else {
            com.argorice.epicysm.client.Diag.info("Items: this model names no hand locator, so the held item is left to Yes Steve"
                    + " Model - drawing a second one on top would only double it");
        }

        if (!weapons.isEmpty()) {
            List<String> names = new ArrayList<>();

            for (Bone bone : weapons) {
                names.add(bone.name());
            }

            com.argorice.epicysm.client.Diag.info("Weapons: this model carries {} of its own ({}); while Epic Fight is fighting they"
                    + " are put away and the item actually in hand is the one on screen. the settings"
                    + " keeps them", names.size(), names);
        }
    }

    /**
     * Puts the model's own weapons away for this frame.
     *
     * A scale of zero, which is how the model's own animations hide a bone,
     * and which carries to everything below it: a bedrock bone is drawn
     * inside its parent's scale.
     */
    private void putOwnWeaponsAway() {
        if (WeaponBones.hideLocked()) {
            for (Bone bone : this.ownWeapons) {
                collapse(bone);
            }
        }

        // The hand locator is left alone now. Collapsing it used to be how
        // Yes Steve Model's own copy of the item was got rid of, but that
    }

    private static void collapse(Bone bone) {
        float[] m = bone.matrices();
        int at = bone.matrixAt();
        m[at + 6] = 0.0F;
        m[at + 7] = 0.0F;
        m[at + 8] = 0.0F;
    }

    /**
     * Whether bones are placed as well as turned.
     *
     * On is the reading that reproduces the converted models exactly; off
     * falls back to turning bones only, which is what this did before and
     * leaves the hands out by a quarter of a block on average.
     */
    /**
     * How much of a bone's move is written into the three numbers beside
     * its rotation.
     */
    private static float placeScale = 1.0F;

    public static float placeScale() {
        return placeScale;
    }

    public static void setPlaceScale(float value) {
        placeScale = value;
    }

    public static boolean placed() {
        return placeScale != 0.0F;
    }

    public static void setPlaced(boolean value) {
        placeScale = value ? 1.0F : 0.0F;
    }

    private void writeTrsRotations(Map<Role, Quaternionf> locals) {
        Vector3f euler = new Vector3f();
        Quaternionf composed = new Quaternionf();

        for (Bone bone : this.heldBones()) {
            float[] rest = bone.original();
            float[] m = bone.matrices();
            int at = bone.matrixAt();
            m[at] = rest[0];
            m[at + 1] = rest[1];
            m[at + 2] = rest[2];
            clearOffset(m, at);
        }

        for (Map.Entry<Role, List<Bone>> entry : this.bones.entrySet()) {
            // The root is the whole model's anchor and the one bone where
            // leaving it alone is not neutral: Yes Steve Model's own
            if (entry.getKey() == Role.ROOT && root != RootMode.EPIC_FIGHT) {
                if (root == RootMode.REST) {
                    for (Bone bone : entry.getValue()) {
                        if (bone.hasMatrix()) {
                            float[] rest = bone.original();
                            float[] m = bone.matrices();
                            int at = bone.matrixAt();
                            m[at] = rest[0];
                            m[at + 1] = rest[1];
                            m[at + 2] = rest[2];
                            clearOffset(m, at);
                        }
                    }
                }

                continue;
            }

            Quaternionf rotation = locals.get((axes & 8) != 0 ? mirrored(entry.getKey()) : entry.getKey());

            if (rotation == null) {
                continue;
            }

            boolean first = true;

            for (Bone bone : entry.getValue()) {
                if (!bone.hasMatrix()) {
                    continue;
                }

                // A Yes Steve Model skeleton carries two bones where a body
                // has one joint: Root, MAllBody, AllBody, UpBody,
                float[] rest = bone.original();

                if (first) {
                    // Where the bone ends up: its own rest, turned by what
                    // Epic Fight's animation did. Both are proper rotations
                    composed.set(fromEuler(order, rest[0], rest[1], rest[2])).mul(rotation);
                    boolean live = YsmPoseSolver.worldFrame();
                    this.continuousEuler(composed, order,
                            live ? this.lastEuler.get(entry.getKey()) : null, euler);

                    if (live) {
                        this.lastEuler.put(entry.getKey(), new float[] { euler.x, euler.y, euler.z });
                    }
                    first = false;
                } else {
                    float[] m2 = bone.matrices();
                    int at2 = bone.matrixAt();
                    m2[at2] = rest[0];
                    m2[at2 + 1] = rest[1];
                    m2[at2 + 2] = rest[2];
                    clearOffset(m2, at2);
                    continue;
                }

                // The same two axes are flipped whichever way the pose was
                // worked out. Changing this at the same time as the
                float x = -euler.x;
                float y = -euler.y;
                float z = euler.z;

                if ((axes & 1) != 0) {
                    x = -x;
                }

                if ((axes & 2) != 0) {
                    y = -y;
                }

                if ((axes & 4) != 0) {
                    z = -z;
                }

                float[] m = bone.matrices();
                int at = bone.matrixAt();
                this.noteJump(entry.getKey(), x, y, z);
                m[at] = x;
                m[at + 1] = y;
                m[at + 2] = z;
                this.writeShift(entry.getKey(), m, at);
            }
        }
    }

    /**
     * Says when a written rotation jumps by more than a right angle in one
     * frame.
     */
    private int jumpsReported;
    private final Map<Role, float[]> lastWritten = new EnumMap<>(Role.class);
    private final Map<Role, float[]> lastEuler = new EnumMap<>(Role.class);

    private void noteJump(Role role, float x, float y, float z) {
        // Against what this wrote last frame, not against what Yes Steve
        // Model computed. Comparing with YSM's own pose only ever says the
        // two poses differ, which they are meant to.
        if (!YsmPoseSolver.worldFrame()) {
            return;
        }

        float[] previous = this.lastWritten.get(role);

        if (previous != null && this.jumpsReported < 8) {
            float biggest = Math.max(Math.abs(x - previous[0]),
                    Math.max(Math.abs(y - previous[1]), Math.abs(z - previous[2])));

            if (biggest > 2.0F) {
                this.jumpsReported++;
                com.argorice.epicysm.client.Diag.info("Skeleton overlay: {} jumped from [{}, {}, {}] to [{}, {}, {}] between two"
                        + " frames of its own pose", role, previous[0], previous[1], previous[2], x, y, z);
            }
        }

        this.lastWritten.put(role, new float[] { x, y, z });
    }

    /** The in-between bones plus every other bone that is not driven. */
    private static List<Bone> notDriven(List<Bone> driven, List<Bone> structural, List<Bone> others) {
        Set<Object> taken = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        for (Bone bone : driven) {
            taken.add(bone.target());
        }

        List<Bone> out = new ArrayList<>(structural);

        for (Bone bone : others) {
            if (bone.hasMatrix() && !taken.contains(bone.target())) {
                out.add(bone);
            }
        }

        return List.copyOf(out);
    }

    /** The bones this setting holds still, ready to be put back to bind. */
    private List<Bone> heldBones() {
        return switch (hold) {
            case NONE -> List.of();
            case PATH -> this.structural;
            case ALL -> this.everythingElse;
        };
    }

    /**
     * Every bone on the way from the model's root down to a joint Epic
     * Fight drives, that is not itself one of them.
     */
    private static List<Bone> structuralBones(@Nullable YsmLiveSkeleton.Skeleton model, List<Object> live,
                                              Map<Role, List<Bone>> byRole) {
        if (model == null || model.bones().size() != live.size()) {
            return List.of();
        }

        Map<String, String> parentOf = new java.util.HashMap<>();

        for (YsmLiveSkeleton.LiveBone bone : model.bones()) {
            if (bone.parent() != null) {
                parentOf.put(bone.name(), bone.parent());
            }
        }

        Set<String> driven = new HashSet<>();

        for (List<Bone> boneList : byRole.values()) {
            for (Bone bone : boneList) {
                driven.add(bone.name());
            }
        }

        Set<String> wanted = new HashSet<>();

        for (String name : driven) {
            String at = parentOf.get(name);

            for (int step = 0; at != null && step < 32; step++) {
                if (!driven.contains(at)) {
                    wanted.add(at);
                }

                at = parentOf.get(at);
            }
        }

        List<Bone> out = new ArrayList<>();

        for (int i = 0; i < live.size(); i++) {
            String name = model.bones().get(i).name();

            if (!wanted.contains(name)) {
                continue;
            }

            Object value = live.get(i);
            Field rotation = rotationFieldOf(value);

            if (rotation == null) {
                continue;
            }

            float[] original = new float[3];

            try {
                if (rotation.get(value) instanceof Vector3f vec) {
                    original[0] = vec.x;
                    original[1] = vec.y;
                    original[2] = vec.z;
                }
            } catch (Throwable ignored) {
            }

            float[] matrices = sharedArray(value, 12);
            float[] quaternions = sharedArray(value, 4);
            int[] offsets = offsetsOf(value, matrices, quaternions);
            Bone bone = new Bone(name, value, rotation, original, offsets[0], offsets[1],
                    matrices, quaternions, pivotOf(value));

            if (bone.hasMatrix()) {
                out.add(bone);
            }
        }

        if (!out.isEmpty()) {
            com.argorice.epicysm.client.Diag.info("Skeleton overlay: {} bone(s) of the body carry no joint and are held still so Yes"
                    + " Steve Model's own animation cannot play under Epic Fight's: {}",
                    out.size(), out.stream().map(Bone::name).toList());
        }

        return List.copyOf(out);
    }

    /** Puts a body bone back where it belongs before Epic Fight turns it. */
    /**
     * Puts Epic Fight's displacement of this joint into the three numbers
     * after the rotation, in the pixels the model counts in.
     */
    private void writeShift(Role role, float[] numbers, int at) {
        org.joml.Vector3f moved = shift ? this.shifts.get(shiftJoint(role)) : null;

        if (moved == null || this.pixelsPerBlock <= 0.0F) {
            clearOffset(numbers, at);
            return;
        }

        numbers[at + 3] = -moved.x * this.pixelsPerBlock;
        numbers[at + 4] = moved.y * this.pixelsPerBlock;
        numbers[at + 5] = moved.z * this.pixelsPerBlock;
    }

    /** Which Epic Fight joint's displacement belongs on this bone. */
    @Nullable
    private static String shiftJoint(Role role) {
        return switch (role) {
            case ROOT -> "Root";
            case BODY, LOWER -> "Torso";
            case CHEST_LOW, CHEST -> "Chest";
            case HEAD_OUTER, HEAD_INNER -> "Head";
            default -> null;
        };
    }

    /**
     * How many of this model's pixels make one Epic Fight block, from the
     * height of its own head bone against the height of Epic Fight's.
     */
    private float pixelsPerBlock;

    private void measureScale() {
        this.pixelsPerBlock = 0.0F;

        for (Role role : new Role[] { Role.HEAD_OUTER, Role.HEAD_INNER, Role.CHEST }) {
            List<Bone> found = this.bones.get(role);

            if (found == null || found.isEmpty()) {
                continue;
            }

            float height = found.get(0).pivot()[1];

            if (height > 1.0F) {
                float reference = role == Role.CHEST ? 1.114F : 1.514F;
                this.pixelsPerBlock = height / reference;
                return;
            }
        }
    }

    private static void clearOffset(float[] numbers, int at) {
        numbers[at + 3] = 0.0F;
        numbers[at + 4] = 0.0F;
        numbers[at + 5] = 0.0F;
    }

    /**
     * Works out how the twelve numbers of a slot are laid out, and keeps the
     * rest pose to build on.
     */
    private boolean adoptMatrices() {
        int rowMajor = 0;
        int columnMajor = 0;
        int usable = 0;

        for (Bone bone : this.allBones) {
            if (!bone.hasMatrix()) {
                continue;
            }

            usable++;
            float[] m = bone.matrices();
            int at = bone.matrixAt();
            float pivotLength = length(bone.pivot()[0], bone.pivot()[1], bone.pivot()[2]);

            if (pivotLength < 0.5F) {
                continue; // a bone at the origin tells nothing apart
            }

            float row = distance(m[at + 3], m[at + 7], m[at + 11], bone.pivot());
            float column = distance(m[at + 9], m[at + 10], m[at + 11], bone.pivot());

            if (row < column && row < 0.35F * pivotLength) {
                rowMajor++;
                this.countSideways(m[at + 3], bone.pivot()[0]);
            } else if (column < row && column < 0.35F * pivotLength) {
                columnMajor++;
                this.countSideways(m[at + 9], bone.pivot()[0]);
            }
        }

        if (rowMajor == 0 && columnMajor == 0) {
            com.argorice.epicysm.client.Diag.info("Skeleton overlay: the twelve numbers per bone do move, but none of them sits where"
                    + " a position would - they are not a transform this mod can write. Checked {} bone(s).{}",
                    usable, this.exampleSlot());
            this.stage = Stage.GIVEN_UP;
            return false;
        }

        this.rowMajorMatrix = rowMajor >= columnMajor;
        this.mirroredSideways = this.sidewaysFlipped > this.sidewaysStraight;
        this.keepOnlyTheLivingOnes();
        this.mode = Mode.MATRIX;
        this.stage = Stage.ACTIVE;
        com.argorice.epicysm.client.Diag.info("Skeleton overlay ACTIVE on the live transforms: {} body bone(s) + {} carried along,"
                        + " {} layout ({} agreed, {} disagreed), sideways axis {}. Epic Fight now poses this model.",
                this.allBones.size(), this.others.size(), this.rowMajorMatrix ? "row-major" : "column-major",
                Math.max(rowMajor, columnMajor), Math.min(rowMajor, columnMajor),
                this.mirroredSideways ? "mirrored" : "straight");
        return true;
    }

    /** One bone's twelve numbers next to its pivot, for the log. */
    private String exampleSlot() {
        for (Bone bone : this.allBones) {
            if (!bone.hasMatrix()) {
                continue;
            }

            StringBuilder text = new StringBuilder(" Example: " + bone.name() + " pivot ["
                    + bone.pivot()[0] + ", " + bone.pivot()[1] + ", " + bone.pivot()[2] + "] rest rotation ["
                    + bone.original()[0] + ", " + bone.original()[1] + ", " + bone.original()[2] + "] at "
                    + bone.matrixAt() + " = [");

            for (int i = 0; i < 12; i++) {
                text.append(i > 0 ? ", " : "").append(bone.matrices()[bone.matrixAt() + i]);
            }

            return text.append(']').toString();
        }

        return "";
    }

    /**
     * Which way round the sideways axis runs. Yes Steve Model and Epic Fight
     * disagree about the sign of it, and a left arm swinging right is exactly
     * the kind of thing nobody should have to guess at: the arms and legs,
     * whose pivots are far from the middle, settle it by themselves.
     */
    private void countSideways(float translationX, float pivotX) {
        if (Math.abs(pivotX) < 0.5F) {
            return;
        }

        if (Math.abs(translationX - pivotX) < Math.abs(translationX + pivotX)) {
            this.sidewaysStraight++;
        } else {
            this.sidewaysFlipped++;
        }
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float distance(float x, float y, float z, float[] pivot) {
        return Math.min(length(x - pivot[0], y - pivot[1], z - pivot[2]),
                length(x + pivot[0], y - pivot[1], z - pivot[2]));
    }

    /* ------------------------------------------------------------------
     * 3. Posing
     * ------------------------------------------------------------------ */

    /** Whether Epic Fight is actually in charge of this player right now. */
    /** The same question, for the bridge. */
    public static boolean fighting(AbstractClientPlayer player) {
        return epicFightInCharge(player) && !get().dormant;
    }

    private static boolean epicFightInCharge(AbstractClientPlayer player) {
        try {
            var patch = EpicFightCapabilities.getEntityPatch(player,
                    yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch.class);
            return patch != null && patch.isEpicFightMode() && !drawingABow(player);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether the player is drawing a bow or loading a crossbow with the use
     * key - the vanilla way, held down.
     */
    private static boolean drawingABow(AbstractClientPlayer player) {
        if (!player.isUsingItem() || !com.argorice.epicysm.client.EpicYsmConfig.bowByYsm()) {
            return false;
        }

        net.minecraft.world.item.ItemStack using = player.getUseItem();
        return !using.isEmpty() && using.getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem;
    }

    private void writePose(AbstractClientPlayer player, float partialTicks) {
        // Only the body on screen is posed; a model that has turned into
        // something else is Yes Steve Model's alone until it turns back.
        if (!this.probe && !this.formStillShown()) {
            return;
        }

        // Before anything is posed: while Epic Fight is fighting, the sword
        // the character was modelled holding goes away, and what is left on
        // screen is the one actually in hand - the one Epic Fight swings.
        if (!this.probe && epicFightInCharge(player)) {
            this.putOwnWeaponsAway();
        }

        if (!this.probe && this.mode == Mode.TRS && epicFightInCharge(player) && this.writeSolved(player, partialTicks)) {
            return;
        }

        if (!this.probe && !epicFightInCharge(player)) {
            this.lastEuler.clear();
            this.lastWritten.clear();
            return;
        }

        if (this.probe && this.mode == Mode.TRS && this.rawAxis() >= 0) {
            this.writeRawAxis(this.rawAxis());
            return;
        }

        Map<Role, Quaternionf> targets = this.probe ? this.probePose() : this.epicFightPose(player, partialTicks);

        if (targets == null) {
            return;
        }

        if (this.mode == Mode.MATRIX) {
            this.writeTransforms(targets);
            return;
        }

        if (this.mode == Mode.TRS) {
            this.writeTrsRotations(targets);
            return;
        }

        Vector3f euler = new Vector3f();

        for (Map.Entry<Role, List<Bone>> entry : this.bones.entrySet()) {
            Quaternionf rotation = targets.get(entry.getKey());

            if (rotation == null) {
                continue;
            }

            // Epic Fight keeps local joint rotations as quaternions; a
            // bedrock skeleton reads them as Z-Y-X degrees with X and Y
            // mirrored - the same convention the geometry converter uses.
            rotation.getEulerAnglesZYX(euler);
            float x = -euler.x * Mth.RAD_TO_DEG;
            float y = -euler.y * Mth.RAD_TO_DEG;
            float z = euler.z * Mth.RAD_TO_DEG;

            for (Bone bone : entry.getValue()) {
                Vector3f vec = bone.vector();

                if (vec != null) {
                    vec.set(x, y, z);
                }
            }
        }
    }

    /**
     * Writes Epic Fight's pose into the transforms Yes Steve Model has just
     * computed, a moment before it draws them.
     */
    private void writeTransforms(Map<Role, Quaternionf> locals) {
        Map<Role, Matrix4f> correction = new EnumMap<>(Role.class);

        // Role order is the order of the body: every parent is done first.
        for (Role role : Role.values()) {
            List<Bone> boneList = this.bones.get(role);

            if (boneList == null || boneList.isEmpty()) {
                continue;
            }

            Matrix4f parent = new Matrix4f();

            for (Role candidate : PARENT_CHAIN.getOrDefault(role, new Role[0])) {
                Matrix4f found = correction.get(candidate);

                if (found != null) {
                    parent.set(found);
                    break;
                }
            }

            float[] pivot = boneList.get(0).pivot();
            float px = this.mirroredSideways ? -pivot[0] : pivot[0];
            Matrix4f own = new Matrix4f(parent)
                    .translate(px, pivot[1], pivot[2])
                    .rotate(this.intoModelSpace(locals.getOrDefault(role, IDENTITY)))
                    .translate(-px, -pivot[1], -pivot[2]);
            correction.put(role, own);

            for (Bone bone : boneList) {
                this.apply(bone, own);
            }
        }

        if (correction.isEmpty()) {
            return;
        }

        for (Bone bone : this.others) {
            Matrix4f nearest = this.nearestCorrection(bone, correction);

            if (nearest != null) {
                this.apply(bone, nearest);
            }
        }
    }

    private static final Quaternionf IDENTITY = new Quaternionf();

    /**
     * Epic Fight's rotation as the model's own space sees it. When the two
     * disagree about the sideways axis, a rotation about that axis stays as
     * it is and the other two turn the other way - which is what a mirror
     * does to a turn.
     */
    private Quaternionf intoModelSpace(Quaternionf rotation) {
        return this.mirroredSideways
                ? new Quaternionf(rotation)
                : new Quaternionf(rotation.x, -rotation.y, -rotation.z, rotation.w);
    }

    @Nullable
    private Matrix4f nearestCorrection(Bone bone, Map<Role, Matrix4f> correction) {
        Matrix4f best = null;
        float bestDistance = Float.MAX_VALUE;

        for (Map.Entry<Role, Matrix4f> entry : correction.entrySet()) {
            List<Bone> boneList = this.bones.get(entry.getKey());

            if (boneList == null || boneList.isEmpty()) {
                continue;
            }

            float[] pivot = boneList.get(0).pivot();
            float distance = length(bone.pivot()[0] - pivot[0], bone.pivot()[1] - pivot[1], bone.pivot()[2] - pivot[2]);

            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getValue();
            }
        }

        return best;
    }

    /** Puts correction * (what YSM computed) back into the bone's slot. */
    private void apply(Bone bone, Matrix4f correction) {
        if (!bone.hasMatrix()) {
            return;
        }

        float[] array = bone.matrices();
        int at = bone.matrixAt();
        Matrix4f current = this.readTransform(array, at);
        correction.mul(current, current);
        this.writeTransform(current, array, at);
    }

    private Matrix4f readTransform(float[] array, int at) {
        Matrix4f matrix = new Matrix4f();

        if (this.rowMajorMatrix) {
            matrix.set(array[at], array[at + 4], array[at + 8], 0.0F,
                    array[at + 1], array[at + 5], array[at + 9], 0.0F,
                    array[at + 2], array[at + 6], array[at + 10], 0.0F,
                    array[at + 3], array[at + 7], array[at + 11], 1.0F);
        } else {
            matrix.set(array[at], array[at + 1], array[at + 2], 0.0F,
                    array[at + 3], array[at + 4], array[at + 5], 0.0F,
                    array[at + 6], array[at + 7], array[at + 8], 0.0F,
                    array[at + 9], array[at + 10], array[at + 11], 1.0F);
        }

        return matrix;
    }

    private void writeTransform(Matrix4f matrix, float[] array, int at) {
        if (this.rowMajorMatrix) {
            array[at] = matrix.m00();
            array[at + 1] = matrix.m10();
            array[at + 2] = matrix.m20();
            array[at + 3] = matrix.m30();
            array[at + 4] = matrix.m01();
            array[at + 5] = matrix.m11();
            array[at + 6] = matrix.m21();
            array[at + 7] = matrix.m31();
            array[at + 8] = matrix.m02();
            array[at + 9] = matrix.m12();
            array[at + 10] = matrix.m22();
            array[at + 11] = matrix.m32();
        } else {
            array[at] = matrix.m00();
            array[at + 1] = matrix.m01();
            array[at + 2] = matrix.m02();
            array[at + 3] = matrix.m10();
            array[at + 4] = matrix.m11();
            array[at + 5] = matrix.m12();
            array[at + 6] = matrix.m20();
            array[at + 7] = matrix.m21();
            array[at + 8] = matrix.m22();
            array[at + 9] = matrix.m30();
            array[at + 10] = matrix.m31();
            array[at + 11] = matrix.m32();
        }
    }

    /**
     * Epic Fight's pose, arranged onto the bones of the standard Yes Steve
     * Model skeleton.
     */
    @Nullable
    private Map<Role, Quaternionf> epicFightPose(AbstractClientPlayer player, float partialTicks) {
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(player, LivingEntityPatch.class);

        if (patch == null || patch.getAnimator() == null) {
            return null;
        }

        Pose pose = patch.getAnimator().getPose(partialTicks);

        if (pose == null) {
            return null;
        }

        Map<String, Quaternionf> change = new java.util.HashMap<>();

        if (retarget) {
            try {
                yesman.epicfight.api.model.Armature biped = yesman.epicfight.gameasset.Armatures.BIPED.get();
                collectChange(biped.rootJoint, new Quaternionf(), new Quaternionf(), pose, change);
            } catch (Throwable t) {
                return null;
            }
        } else {
            // What this did before the rest orientations were taken into
            // account: Epic Fight's raw per-joint turns, multiplied straight
            Quaternionf root = local(pose, "Root");
            Quaternionf torso = root.mul(local(pose, "Torso"), new Quaternionf());
            Quaternionf chest = torso.mul(local(pose, "Chest"), new Quaternionf());
            change.put("Root", root);
            change.put("Torso", torso);
            change.put("Chest", chest);
            change.put("Head", chest.mul(local(pose, "Head"), new Quaternionf()));
            change.put("Shoulder_L", chest);
            change.put("Shoulder_R", chest);

            for (String side : new String[] { "L", "R" }) {
                Quaternionf arm = chest.mul(local(pose, "Shoulder_" + side), new Quaternionf())
                        .mul(local(pose, "Arm_" + side));
                Quaternionf forearm = arm.mul(local(pose, "Hand_" + side), new Quaternionf());
                Quaternionf thigh = root.mul(local(pose, "Thigh_" + side), new Quaternionf());
                change.put("Arm_" + side, arm);
                change.put("Hand_" + side, forearm);
                change.put("Tool_" + side, forearm.mul(local(pose, "Tool_" + side), new Quaternionf()));
                change.put("Thigh_" + side, thigh);
                change.put("Leg_" + side, thigh.mul(local(pose, "Leg_" + side), new Quaternionf()));
            }
        }

        // The spine, joint for joint, taken from the mapping the converted
        // models use - the ones that come out right.
        Map<Role, Quaternionf> world = new EnumMap<>(Role.class);
        put(world, change, Role.ROOT, "Root");
        put(world, change, Role.BODY, "Root");
        put(world, change, Role.LOWER, "Root");
        put(world, change, Role.CHEST_LOW, "Torso");
        put(world, change, Role.CHEST, "Chest");
        put(world, change, Role.HEAD_OUTER, "Head");
        put(world, change, Role.HEAD_INNER, "Head");

        for (String side : new String[] { "L", "R" }) {
            boolean left = "L".equals(side);
            put(world, change, left ? Role.ARM_L : Role.ARM_R, "Arm_" + side);
            put(world, change, left ? Role.FOREARM_L : Role.FOREARM_R, "Hand_" + side);
            put(world, change, left ? Role.HAND_L : Role.HAND_R, "Tool_" + side);
            put(world, change, left ? Role.THIGH_L : Role.THIGH_R, "Thigh_" + side);
            put(world, change, left ? Role.SHIN_L : Role.SHIN_R, "Leg_" + side);
            put(world, change, left ? Role.FOOT_L : Role.FOOT_R, "Leg_" + side);
        }

        this.shifts.clear();

        if (shift) {
            // How far Epic Fight's animation carries each joint away from
            // where it rests, in blocks. A lunge, a step, a duck: the whole
            collectShift(pose, this.shifts);
        }

        return this.toLocalRotations(world);
    }

    /** Joint name -> how far the animation moved it, in Epic Fight blocks. */
    private final Map<String, org.joml.Vector3f> shifts = new java.util.HashMap<>();

    private static void collectShift(Pose pose, Map<String, org.joml.Vector3f> out) {
        for (String joint : new String[] { "Root", "Torso", "Chest", "Head" }) {
            try {
                if (!pose.hasTransform(joint)) {
                    continue;
                }

                // From the matrix, like the turn beside it, so that both
                // come out of the same object rather than from two of Epic
                // Fight's conventions.
                var moved = pose.orElseEmpty(joint).toMatrix();

                if (moved != null) {
                    out.put(joint, new org.joml.Vector3f(moved.m30, moved.m31, moved.m32));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void put(Map<Role, Quaternionf> world, Map<String, Quaternionf> change, Role role, String joint) {
        Quaternionf found = change.get(joint);

        if (found != null) {
            world.put(role, found);
        }
    }

    /**
     * How much Epic Fight's animation has turned each joint away from where
     * the joint rests, measured in model space.
     */
    private static void collectChange(Joint joint, Quaternionf bindWorld, Quaternionf posedWorld,
                                      Pose pose, Map<String, Quaternionf> out) {
        Quaternionf bindLocal = rotationOf(joint.getLocalTransform());
        Quaternionf bind = bindWorld.mul(bindLocal, new Quaternionf());
        Quaternionf posed = posedWorld.mul(bindLocal, new Quaternionf()).mul(local(pose, joint.getName()));
        out.put(joint.getName(), posed.mul(bind.invert(new Quaternionf()), new Quaternionf()));

        for (Joint child : joint.getSubJoints()) {
            collectChange(child, bind, posed, pose, out);
        }
    }

    /** The rotation part of one of Epic Fight's matrices. */
    private static Quaternionf rotationOf(yesman.epicfight.api.utils.math.OpenMatrix4f matrix) {
        Matrix3f basis = new Matrix3f(
                matrix.m00, matrix.m01, matrix.m02,
                matrix.m10, matrix.m11, matrix.m12,
                matrix.m20, matrix.m21, matrix.m22);
        return new Quaternionf().setFromNormalized(basis).normalize();
    }

    /** Which bone above this one the model actually has, closest first. */
    private static final Map<Role, Role[]> PARENT_CHAIN = new EnumMap<>(Role.class);

    static {
        Role[] spine = { Role.CHEST, Role.CHEST_LOW, Role.BODY, Role.ROOT };
        PARENT_CHAIN.put(Role.ROOT, new Role[0]);
        PARENT_CHAIN.put(Role.BODY, new Role[] { Role.ROOT });
        PARENT_CHAIN.put(Role.CHEST_LOW, new Role[] { Role.BODY, Role.ROOT });
        PARENT_CHAIN.put(Role.CHEST, new Role[] { Role.CHEST_LOW, Role.BODY, Role.ROOT });
        PARENT_CHAIN.put(Role.LOWER, new Role[] { Role.BODY, Role.ROOT });
        PARENT_CHAIN.put(Role.HEAD_OUTER, spine);
        PARENT_CHAIN.put(Role.HEAD_INNER, prepend(Role.HEAD_OUTER, spine));
        PARENT_CHAIN.put(Role.ARM_L, spine);
        PARENT_CHAIN.put(Role.ARM_R, spine);
        PARENT_CHAIN.put(Role.FOREARM_L, prepend(Role.ARM_L, spine));
        PARENT_CHAIN.put(Role.FOREARM_R, prepend(Role.ARM_R, spine));
        PARENT_CHAIN.put(Role.HAND_L, prepend(Role.FOREARM_L, prepend(Role.ARM_L, spine)));
        PARENT_CHAIN.put(Role.HAND_R, prepend(Role.FOREARM_R, prepend(Role.ARM_R, spine)));

        Role[] hips = { Role.LOWER, Role.BODY, Role.ROOT };
        PARENT_CHAIN.put(Role.THIGH_L, hips);
        PARENT_CHAIN.put(Role.THIGH_R, hips);
        PARENT_CHAIN.put(Role.SHIN_L, prepend(Role.THIGH_L, hips));
        PARENT_CHAIN.put(Role.SHIN_R, prepend(Role.THIGH_R, hips));
        PARENT_CHAIN.put(Role.FOOT_L, prepend(Role.SHIN_L, prepend(Role.THIGH_L, hips)));
        PARENT_CHAIN.put(Role.FOOT_R, prepend(Role.SHIN_R, prepend(Role.THIGH_R, hips)));
    }

    private static Role[] prepend(Role first, Role[] rest) {
        Role[] out = new Role[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    /**
     * Turns "where this joint should point" into "what to write on this
     * bone", using the parent each model actually has.
     */
    private Map<Role, Quaternionf> toLocalRotations(Map<Role, Quaternionf> world) {
        Map<Role, Quaternionf> out = new EnumMap<>(Role.class);

        for (Role boneRole : this.bones.keySet()) {
            Quaternionf target = world.get(boneRole);

            if (target == null) {
                continue;
            }

            Quaternionf parent = new Quaternionf();

            for (Role candidate : PARENT_CHAIN.getOrDefault(boneRole, new Role[0])) {
                if (this.bones.containsKey(candidate) && world.containsKey(candidate)) {
                    parent.set(world.get(candidate));
                    break;
                }
            }

            out.put(boneRole, parent.invert().mul(target));
        }

        return out;
    }

    /** A joint's local animation rotation, or no rotation at all. */
    /** The turn an animation adds at one joint, taken from its matrix. */
    private static Quaternionf local(Pose pose, String joint) {
        try {
            if (!pose.hasTransform(joint)) {
                return new Quaternionf();
            }

            var turned = pose.orElseEmpty(joint).toMatrix();
            return turned == null ? new Quaternionf() : rotationOf(turned);
        } catch (Throwable t) {
            return new Quaternionf();
        }
    }

    /**
     * A pose nobody could mistake for an animation: head turned hard right,
     * right arm straight up, body leaning. the settings puts it on the
     * model so it takes one glance - not a log file - to tell whether these
     * writes reach the screen at all.
     */
    private Map<Role, Quaternionf> probePose() {
        Map<Role, Quaternionf> out = new EnumMap<>(Role.class);
        boolean all = this.probePart == ProbePart.ALL;

        if (all || this.probePart == ProbePart.HEAD) {
            this.put(out, new Quaternionf().rotateY(Mth.DEG_TO_RAD * 60.0F), Role.HEAD_OUTER, Role.HEAD_INNER);
        }

        if (all || this.probePart == ProbePart.ARM) {
            this.put(out, new Quaternionf().rotateX(Mth.DEG_TO_RAD * 150.0F), Role.ARM_R);
            this.put(out, new Quaternionf().rotateZ(Mth.DEG_TO_RAD * -60.0F), Role.ARM_L);
        }

        if (all || this.probePart == ProbePart.BODY) {
            this.put(out, new Quaternionf().rotateZ(Mth.DEG_TO_RAD * 20.0F), Role.BODY, Role.CHEST_LOW, Role.CHEST);
        }

        return out;
    }

    /** Which of the three rotation numbers the raw probe writes, or -1. */
    private int rawAxis() {
        return switch (this.probePart) {
            case RX -> 0;
            case RY -> 1;
            case RZ -> 2;
            default -> -1;
        };
    }

    /**
     * Writes a quarter turn straight into one of the three numbers of the
     * head, and touches nothing else.
     */
    private void writeRawAxis(int axis) {
        // The right arm, not the head: a quarter turn written into the head
        // moved nothing at all, while the arm was seen responding. A
        // measurement nobody can see is not a measurement.
        List<Bone> arm = this.bones.get(Role.ARM_R);

        if (arm == null || arm.isEmpty()) {
            arm = this.bones.get(Role.FOREARM_R);
        }

        if (arm == null || arm.isEmpty()) {
            return;
        }

        List<Bone> all = new ArrayList<>(arm);

        StringBuilder names = new StringBuilder();

        for (Bone bone : all) {
            if (!bone.hasMatrix()) {
                continue;
            }

            names.append(names.length() == 0 ? "" : ", ").append(bone.name());
            float[] m = bone.matrices();
            int at = bone.matrixAt();
            float[] rest = bone.original();
            m[at] = rest[0];
            m[at + 1] = rest[1];
            m[at + 2] = rest[2];
            m[at + axis] += (float) (Math.PI / 4.0);
            clearOffset(m, at);
        }

        if (this.announcedRawAxis != axis) {
            this.announcedRawAxis = axis;
            com.argorice.epicysm.client.Diag.info("Skeleton overlay: writing a quarter turn into number #{} of {}", axis, names);
        }
    }

    /** Puts the rotation on the first of these bones the model has. */
    private void put(Map<Role, Quaternionf> out, Quaternionf rotation, Role... candidates) {
        for (Role candidate : candidates) {
            if (this.bones.containsKey(candidate)) {
                out.put(candidate, rotation);
                return;
            }
        }
    }

    /** Throws away everything the measurement did not see move. */
    private void keepOnlyTheLivingOnes() {
        Set<Class<?>> living = new HashSet<>();

        for (int i = 0; i < this.allBones.size(); i++) {
            if (this.moved[i]) {
                living.add(this.allBones.get(i).target().getClass());
            }
        }

        Map<Role, List<Bone>> keptByRole = new EnumMap<>(Role.class);
        Map<Role, List<Bone>> stillByRole = new EnumMap<>(Role.class);

        for (int i = 0; i < this.allBones.size(); i++) {
            Bone bone = this.allBones.get(i);

            if (!living.contains(bone.target().getClass())) {
                continue;
            }

            String name = bone.name();
            Role boneRole = name == null ? null : ROLE_BY_NAME.get(name.toLowerCase(Locale.ROOT));

            if (boneRole == null) {
                continue;
            }

            (this.moved[i] ? keptByRole : stillByRole)
                    .computeIfAbsent(boneRole, key -> new ArrayList<>()).add(bone);
        }

        // A bone can legitimately hold still - a planted foot - so a role
        // nothing moved in keeps its candidates rather than being dropped.
        for (Map.Entry<Role, List<Bone>> entry : stillByRole.entrySet()) {
            keptByRole.computeIfAbsent(entry.getKey(), key -> entry.getValue());
        }

        List<Bone> flat = new ArrayList<>();
        keptByRole.values().forEach(flat::addAll);

        if (keptByRole.size() < MIN_BONES) {
            return;
        }

        this.restoreAllBut(flat);
        this.bones = keptByRole;
        this.allBones = flat;
    }

    /** Puts back the rotations of every bone this overlay is dropping. */
    private void restoreAllBut(List<Bone> kept) {
        Set<Object> keeping = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        for (Bone bone : kept) {
            keeping.add(bone.target());
        }

        for (Bone bone : this.allBones) {
            if (keeping.contains(bone.target())) {
                continue;
            }

            Vector3f vec = bone.vector();

            if (vec != null) {
                vec.set(bone.original()[0], bone.original()[1], bone.original()[2]);
            }
        }
    }

    /** Puts back the rotations that were there before the overlay started. */
    private void restore() {
        for (Bone bone : this.allBones) {
            Vector3f vec = bone.vector();

            if (vec != null) {
                vec.set(bone.original()[0], bone.original()[1], bone.original()[2]);
            }
        }
    }

    /* ------------------------------------------------------------------
     * Reflection helpers - every one tolerates hostile objects.
     * ------------------------------------------------------------------ */

    private static boolean sameTexture(ResourceLocation left, ResourceLocation right) {
        return left == null ? right == null : left.equals(right);
    }

    @Nullable
    private static java.nio.file.Path ysmJarPath() {
        try {
            var modFile = net.neoforged.fml.ModList.get().getModFileById("yes_steve_model");
            return modFile == null ? null : modFile.getFile().getFilePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Children worth walking. Every access is guarded: in a modded game some
     * maps throw on iteration (Connector's redirecting maps do), and a walker
     * must survive that rather than die on it.
     */
    private static List<Object> childrenOf(Object value) {
        List<Object> children = new ArrayList<>();

        try {
            if (value instanceof Map<?, ?> map) {
                for (Object element : map.values()) {
                    if (children.size() >= 1024) {
                        break;
                    }

                    children.add(element);
                }

                return children;
            }

            if (value instanceof Collection<?> collection) {
                for (Object element : collection) {
                    if (children.size() >= 1024) {
                        break;
                    }

                    children.add(element);
                }

                return children;
            }

            if (value instanceof Optional<?> optional) {
                optional.ifPresent(children::add);
                return children;
            }

            if (value.getClass().isArray()) {
                if (!value.getClass().getComponentType().isPrimitive()) {
                    int length = Math.min(Array.getLength(value), 1024);

                    for (int i = 0; i < length; i++) {
                        children.add(Array.get(value, i));
                    }
                }

                return children;
            }

            if (isPlain(value)) {
                return children;
            }

            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : safeFields(type)) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                    }

                    try {
                        if (field.trySetAccessible()) {
                            children.add(field.get(value));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
            // This object refuses to be walked; skip it entirely.
        }

        return children;
    }

    private static boolean isPlain(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?> || value instanceof Class<?>
                || value instanceof UUID || value instanceof ResourceLocation;
    }

    private static Field[] safeFields(Class<?> type) {
        try {
            return type.getDeclaredFields();
        } catch (Throwable t) {
            return new Field[0];
        }
    }

    private static boolean isYsmClass(Class<?> type) {
        return type != null && type.getName().startsWith(YSM_PACKAGE);
    }
}
