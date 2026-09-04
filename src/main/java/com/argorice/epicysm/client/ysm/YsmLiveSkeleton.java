package com.argorice.epicysm.client.ysm;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

import com.argorice.epicysm.EpicYsm;

/**
 * Reads the skeleton Yes Steve Model has in memory for the model it is
 * currently showing: bone names, their pivots, and who hangs off whom.
 */
public final class YsmLiveSkeleton {
    private static final String YSM_PACKAGE = "com.elfmcys.";
    private static final int MAX_NODES = 200_000;
    private static final int MAX_DEPTH = 16;
    private static final int MIN_BONES = 6;

    /** One bone as YSM holds it: name, pivot in model pixels, parent. */
    public record LiveBone(String name, float pivotX, float pivotY, float pivotZ, @Nullable String parent) {
    }

    /**
     * One model's bones, in the order YSM's own map yields them, together
     * with the live objects they were read from - which is what the pose
     * overlay needs, since it writes back into them.
     */
    /**
     * @param repeats how many bones were dropped because a bone of that
     *                name had already been seen - which is how a bag of
     *                every model Yes Steve Model has loaded gives itself
     *                away: one rig has one LeftArm, a bag has a dozen
     */
    public record Skeleton(List<LiveBone> bones, List<Object> objects, Object owner, int repeats) {
        public Skeleton(List<LiveBone> bones, List<Object> objects, Object owner) {
            this(bones, objects, owner, 0);
        }

        public Set<String> names() {
            Set<String> names = new LinkedHashSet<>();

            for (LiveBone bone : this.bones) {
                names.add(bone.name());
            }

            return names;
        }
    }

    private YsmLiveSkeleton() {
    }

    /**
     * Every skeleton reachable from YSM right now. Usually one - the model
     * on screen - but a first-person arm model or a second player adds
     * more, so the caller is given all of them and picks.
     */
    public static List<Skeleton> read(@Nullable AbstractClientPlayer player, Object renderer) {
        try {
            return walk(player, renderer, null).skeletons;
        } catch (Throwable t) {
            EpicYsm.LOGGER.debug("Could not read Yes Steve Model's live skeleton", t);
            return List.of();
        }
    }

    /** The skeleton of the model being drawn with this texture. */
    @Nullable
    public static Skeleton readFor(@Nullable AbstractClientPlayer player, Object renderer,
                                   @Nullable ResourceLocation texture) {
        return readFor(player, renderer, texture, Set.of());
    }

    /**
     * @param rejected model objects already tried and found not to be the
     *                 one Yes Steve Model animates (a cached copy with the
     *                 same bones); they are passed over
     */
    @Nullable
    public static Skeleton readFor(@Nullable AbstractClientPlayer player, Object renderer,
                                   @Nullable ResourceLocation texture, Set<Object> rejected) {
        try {
            Found found = walk(player, renderer, texture);

            if (found.skeletons.isEmpty()) {
                return null;
            }

            // Yes Steve Model can hold the same model twice: the copy in its
            // cache and the copy built for this player. Only the second is
            // animated. The one that hangs below the object holding the
            // player's texture (or the player himself) is preferred; a plain
            // nearest-common-ancestor distance breaks ties after that.
            List<Object> holders = new ArrayList<>(found.textureHolders);
            holders.addAll(found.playerHolders);
            Skeleton best = null;
            int bestBelow = Integer.MAX_VALUE;
            int bestDistance = Integer.MAX_VALUE;
            boolean bestIsOneRig = false;
            int copies = 0;

            for (Skeleton skeleton : found.skeletons) {
                if (rejected.contains(skeleton.owner())) {
                    continue;
                }

                int below = Integer.MAX_VALUE;
                int distance = Integer.MAX_VALUE;

                for (Object holder : holders) {
                    int down = stepsBelow(found.parents, holder, skeleton.owner());

                    if (down >= 0 && down < below) {
                        below = down;
                    }

                    int apart = distance(found.parents, holder, skeleton.owner());

                    if (apart >= 0 && apart < distance) {
                        distance = apart;
                    }
                }

                if (distance == Integer.MAX_VALUE) {
                    continue;
                }

                copies++;

                // A rig has one bone called LeftArm. Something that has
                // seven of them is not a model, it is every model Yes
                boolean oneRig = skeleton.repeats() == 0;
                boolean better;

                if (oneRig != bestIsOneRig) {
                    better = oneRig;
                } else if (below != bestBelow) {
                    better = below < bestBelow;
                } else {
                    better = distance < bestDistance;
                }

                if (better) {
                    bestBelow = below;
                    bestDistance = distance;
                    bestIsOneRig = oneRig;
                    best = skeleton;
                }
            }

            if (best != null) {
                com.argorice.epicysm.client.Diag.info("Live skeleton chosen for this model: {} bone(s), {} repeated name(s),"
                        + " {} step(s) from the object holding the texture, {} below it; {} candidate(s), {} passed over",
                        best.bones().size(), best.repeats(), bestDistance,
                        bestBelow == Integer.MAX_VALUE ? "not" : bestBelow, copies, rejected.size());
                rememberSize(found, texture);
            }

            return best;
        } catch (Throwable t) {
            EpicYsm.LOGGER.debug("Could not read Yes Steve Model's live skeleton", t);
            return null;
        }
    }

    private static final class Found {
        final List<Skeleton> skeletons = new ArrayList<>();
        final List<Object> textureHolders = new ArrayList<>();
        final List<Object> playerHolders = new ArrayList<>();
        final Map<Object, Object> parents = new IdentityHashMap<>();
    }

    /** Steps down from an ancestor to a node, or -1 if it is not above it. */
    private static int stepsBelow(Map<Object, Object> parents, Object above, Object node) {
        Object at = node;

        for (int step = 0; at != null && step <= MAX_DEPTH + 1; step++) {
            if (at == above) {
                return step;
            }

            at = parents.get(at);
        }

        return -1;
    }

    /** Steps between two nodes through their nearest common ancestor. */
    private static int distance(Map<Object, Object> parents, Object left, Object right) {
        Map<Object, Integer> up = new IdentityHashMap<>();
        Object at = left;

        for (int step = 0; at != null && step <= MAX_DEPTH + 1; step++) {
            up.put(at, step);
            at = parents.get(at);
        }

        at = right;

        for (int step = 0; at != null && step <= MAX_DEPTH + 1; step++) {
            Integer other = up.get(at);

            if (other != null) {
                return other + step;
            }

            at = parents.get(at);
        }

        return -1;
    }

    private static Found walk(@Nullable AbstractClientPlayer player, Object renderer,
                              @Nullable ResourceLocation texture) {
        Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Object, Integer> depths = new IdentityHashMap<>();
        Deque<Object> queue = new ArrayDeque<>();
        Found found = new Found();
        Set<Object> seenOwners = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        for (Object seed : seeds(player, renderer)) {
            if (seed != null && depths.putIfAbsent(seed, 0) == null) {
                queue.add(seed);
            }
        }

        int nodes = 0;

        while (!queue.isEmpty() && nodes < MAX_NODES) {
            Object value = queue.poll();

            if (value == null || !visited.add(value)) {
                continue;
            }

            nodes++;
            int depth = depths.getOrDefault(value, 0);

            if (depth < MAX_DEPTH) {
                for (Object child : childrenOf(value)) {
                    if (child != null && !visited.contains(child) && depths.putIfAbsent(child, depth + 1) == null) {
                        found.parents.put(child, value);
                        queue.add(child);
                    }
                }
            }

            if (!isYsmClass(value.getClass())) {
                continue;
            }

            if (texture != null && found.textureHolders.size() < 16 && holdsTexture(value, texture)) {
                found.textureHolders.add(value);
            }

            if (player != null && found.playerHolders.size() < 32 && holdsPlayer(value, player)) {
                found.playerHolders.add(value);
            }

            Skeleton skeleton = skeletonOf(value);

            // The same model object is reachable by several routes; a second
            // object with the same bones is a second copy and is kept.
            if (skeleton != null && seenOwners.add(skeleton.objects().isEmpty() ? skeleton.owner() : skeleton.objects().get(0))) {
                found.skeletons.add(skeleton);
            }
        }

        return found;
    }

    /** The size Yes Steve Model draws this model at, as it has it in memory. */
    private static final Map<ResourceLocation, float[]> drawnSizes = new java.util.HashMap<>();

    /** {width, height} Yes Steve Model draws this texture's model at, or null if not found. */
    @Nullable
    public static float[] drawnSize(@Nullable ResourceLocation texture) {
        return texture == null ? null : drawnSizes.get(texture);
    }

    private static void rememberSize(Found found, @Nullable ResourceLocation texture) {
        if (texture == null || found.playerHolders.isEmpty()) {
            return;
        }

        // Out from the objects that hold this very player - Yes Steve
        // Model's record of him, and the thing that animates his model -
        Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> queue = new ArrayDeque<>();
        Map<Object, Integer> depths = new IdentityHashMap<>();
        Object nearest = null;
        int nearestDepth = Integer.MAX_VALUE;

        for (Object seed : found.playerHolders) {
            depths.put(seed, 0);
            queue.add(seed);
        }

        int nodes = 0;

        while (!queue.isEmpty() && nodes < 20_000) {
            Object value = queue.poll();

            if (value == null || !visited.add(value)) {
                continue;
            }

            nodes++;
            int depth = depths.getOrDefault(value, 0);

            if (depth >= nearestDepth) {
                continue;
            }

            if (isYsmClass(value.getClass()) && holdsSize(value)) {
                nearest = value;
                nearestDepth = depth;
                continue;
            }

            if (depth < 6) {
                for (Object child : childrenOf(value)) {
                    if (child != null && !visited.contains(child) && depths.putIfAbsent(child, depth + 1) == null) {
                        queue.add(child);
                    }
                }
            }
        }

        if (nearest == null) {
            com.argorice.epicysm.client.Diag.info("Live skeleton: Yes Steve Model's own description of this model - the size it is"
                    + " drawn at - was not found within six steps of the player; seven tenths is assumed, which"
                    + " is what nearly every model uses.");
            return;
        }

        float[] size = sizeOf(nearest);

        if (size == null) {
            return;
        }

        float[] known = drawnSizes.put(texture, size);

        if (known == null || known[0] != size[0] || known[1] != size[1]) {
            com.argorice.epicysm.client.Diag.info("Live skeleton: Yes Steve Model draws this model {} wide and {} tall to its own"
                    + " unit, read from the model's own description {} step(s) from the player. That is the"
                    + " ruler every animation's travel and the hand's place are measured with.",
                    size[0], size[1], nearestDepth);
        }
    }

    /** Whether one of this object's own fields is this very player. */
    private static boolean holdsPlayer(Object value, Object player) {
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
                        || !field.getType().isInstance(player)) {
                    continue;
                }

                try {
                    if (field.trySetAccessible() && field.get(value) == player) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return false;
    }

    /** Whether this object is a model's description: two final fractions, switches, a map. */
    private static boolean holdsSize(Object value) {
        Class<?> type = value.getClass();

        if (!isYsmClass(type) || type.isEnum() || type.isRecord() || type.getSuperclass() != Object.class) {
            return false;
        }

        int floats = 0;
        int booleans = 0;
        int maps = 0;
        int strings = 0;

        for (Field field : safeFields(type)) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            Class<?> kind = field.getType();

            if (kind == float.class) {
                if (!Modifier.isFinal(field.getModifiers())) {
                    return false;
                }

                floats++;
            } else if (kind == boolean.class) {
                booleans++;
            } else if (kind.isPrimitive()) {
                // An int, a long, a double: a counter or a clock, not a description.
                return false;
            } else if (Map.class.isAssignableFrom(kind)) {
                maps++;
            } else if (kind == String.class) {
                strings++;
            }
        }

        return floats == 2 && booleans >= 2 && maps >= 1 && strings >= 1 && sizeOf(value) != null;
    }

    @Nullable
    private static float[] sizeOf(Object value) {
        float[] out = new float[2];
        int at = 0;

        for (Field field : safeFields(value.getClass())) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != float.class) {
                continue;
            }

            try {
                if (!field.trySetAccessible()) {
                    return null;
                }

                float read = field.getFloat(value);

                if (!Float.isFinite(read) || read < 0.05F || read > 4.0F || at >= 2) {
                    return null;
                }

                out[at++] = read;
            } catch (Throwable t) {
                return null;
            }
        }

        return at == 2 ? out : null;
    }

    /** Whether one of this object's own fields is that very texture. */
    private static boolean holdsTexture(Object value, ResourceLocation texture) {
        for (Class<?> type = value.getClass(); type != null && isYsmClass(type); type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }

                try {
                    if (field.trySetAccessible() && texture.equals(field.get(value))) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return false;
    }

    /**
     * The skeleton this object owns, if it owns one: a map or list holding
     * bone objects, plus the root-to-leaf chains beside it.
     */
    @Nullable
    private static Skeleton skeletonOf(Object model) {
        Map<Object, String> names = new IdentityHashMap<>();
        List<Object> ordered = new ArrayList<>();
        List<List<Object>> chains = new ArrayList<>();

        for (Class<?> type = model.getClass(); type != null && isYsmClass(type); type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }

                Object held;

                try {
                    held = field.trySetAccessible() ? field.get(model) : null;
                } catch (Throwable ignored) {
                    continue;
                }

                List<Object> elements = elementsOf(held);

                if (elements.isEmpty()) {
                    continue;
                }

                List<Object> bones = new ArrayList<>();

                for (Object element : elements) {
                    if (element != null && isBone(element)) {
                        bones.add(element);
                    }
                }

                if (bones.isEmpty()) {
                    // A list whose entries are themselves lists of bones is
                    // a set of chains, and Yes Steve Model keeps three such
                    for (Object element : elements) {
                        List<Object> inner = elementsOf(element);

                        if (inner.isEmpty()) {
                            continue;
                        }

                        List<Object> innerBones = new ArrayList<>();

                        for (Object candidate : inner) {
                            if (candidate != null && isBone(candidate)) {
                                innerBones.add(candidate);
                            }
                        }

                        if (innerBones.size() == inner.size() && innerBones.size() > 1) {
                            chains.add(innerBones);

                            for (Object candidate : innerBones) {
                                if (names.putIfAbsent(candidate, nameOf(candidate)) == null) {
                                    ordered.add(candidate);
                                }
                            }
                        }
                    }

                    continue;
                }

                if (bones.size() != elements.size()) {
                    continue;
                }

                // A List is one root-to-leaf chain; the Map is every bone.
                if (held instanceof Map<?, ?>) {
                    for (Object bone : bones) {
                        if (names.putIfAbsent(bone, nameOf(bone)) == null) {
                            ordered.add(bone);
                        }
                    }
                } else {
                    chains.add(bones);
                }
            }
        }

        // The chains alone also describe every bone, which is what a model
        // that keeps no map of them still gives away.
        for (List<Object> chain : chains) {
            for (Object bone : chain) {
                if (names.putIfAbsent(bone, nameOf(bone)) == null) {
                    ordered.add(bone);
                }
            }
        }

        if (ordered.size() < MIN_BONES) {
            return null;
        }

        Map<Object, Object> parents = new IdentityHashMap<>();

        for (List<Object> chain : chains) {
            for (int i = 1; i < chain.size(); i++) {
                parents.putIfAbsent(chain.get(i), chain.get(i - 1));
            }
        }

        List<LiveBone> bones = new ArrayList<>();
        List<Object> objects = new ArrayList<>();
        Map<String, Boolean> used = new LinkedHashMap<>();
        int repeats = 0;
        for (Object bone : ordered) {
            String name = names.get(bone);

            if (name == null || name.isEmpty()) {
                continue;
            }

            if (used.putIfAbsent(name, Boolean.TRUE) != null) {
                repeats++;
                continue;
            }

            float[] pivot = pivotOf(bone);
            Object parent = parents.get(bone);
            bones.add(new LiveBone(name, pivot[0], pivot[1], pivot[2],
                    parent == null ? null : names.get(parent)));
            objects.add(bone);
        }

        List<LiveBone> whole = fillParents(bones, repeats);

        return whole.size() < MIN_BONES ? null
                : new Skeleton(List.copyOf(whole), List.copyOf(objects), model, repeats);
    }

    /** Which bone a named bone hangs from, when Yes Steve Model does not say. */
    private static final Map<String, String[]> KNOWN_PARENTS = new LinkedHashMap<>();

    /** The bone names this rig always uses, lower case, for anything that needs to tell a body part from a prop. */
    public static Set<String> knownNames() {
        return java.util.Collections.unmodifiableSet(KNOWN_PARENTS.keySet());
    }

    static {
        KNOWN_PARENTS.put("mallbody", new String[] { "root" });
        KNOWN_PARENTS.put("allbody", new String[] { "mallbody", "root" });
        KNOWN_PARENTS.put("upbody", new String[] { "allbody", "mallbody", "root" });
        KNOWN_PARENTS.put("mupbody", new String[] { "upbody", "allbody" });
        KNOWN_PARENTS.put("downbody", new String[] { "allbody", "mallbody", "root" });
        KNOWN_PARENTS.put("mupperbody", new String[] { "upbody", "mupbody", "allbody" });
        KNOWN_PARENTS.put("upperbody", new String[] { "mupperbody", "upbody", "allbody" });
        KNOWN_PARENTS.put("breast", new String[] { "upperbody", "mupperbody" });
        KNOWN_PARENTS.put("collar", new String[] { "upperbody", "mupperbody" });
        KNOWN_PARENTS.put("arm", new String[] { "upperbody", "mupperbody", "upbody" });
        KNOWN_PARENTS.put("leftarm", new String[] { "arm", "upperbody", "mupperbody" });
        KNOWN_PARENTS.put("rightarm", new String[] { "arm", "upperbody", "mupperbody" });
        KNOWN_PARENTS.put("leftforearm", new String[] { "leftarm" });
        KNOWN_PARENTS.put("rightforearm", new String[] { "rightarm" });
        KNOWN_PARENTS.put("lefthand", new String[] { "leftforearm", "leftarm" });
        KNOWN_PARENTS.put("righthand", new String[] { "rightforearm", "rightarm" });
        KNOWN_PARENTS.put("lefthandlocator", new String[] { "lefthand", "leftforearm" });
        KNOWN_PARENTS.put("righthandlocator", new String[] { "righthand", "rightforearm" });
        KNOWN_PARENTS.put("allhead", new String[] { "upperbody", "mupperbody", "upbody" });
        KNOWN_PARENTS.put("mhead", new String[] { "allhead" });
        KNOWN_PARENTS.put("head", new String[] { "mhead", "allhead" });
        KNOWN_PARENTS.put("hair", new String[] { "head", "mhead", "allhead" });
        KNOWN_PARENTS.put("face", new String[] { "head", "mhead", "allhead" });
        KNOWN_PARENTS.put("leg", new String[] { "downbody", "allbody" });
        KNOWN_PARENTS.put("leftleg", new String[] { "leg", "downbody", "allbody" });
        KNOWN_PARENTS.put("rightleg", new String[] { "leg", "downbody", "allbody" });
        KNOWN_PARENTS.put("leftlowerleg", new String[] { "leftleg" });
        KNOWN_PARENTS.put("rightlowerleg", new String[] { "rightleg" });
        KNOWN_PARENTS.put("leftfoot", new String[] { "leftlowerleg", "leftleg" });
        KNOWN_PARENTS.put("rightfoot", new String[] { "rightlowerleg", "rightleg" });
    }

    /**
     * Fills in the bone above wherever Yes Steve Model did not say, from
     * the names this rig always uses. Returns the same bones in the same
     * order, and says in the log how many needed it.
     */
    public static List<LiveBone> fillParents(List<LiveBone> bones) {
        return fillParents(bones, 0);
    }

    private static List<LiveBone> fillParents(List<LiveBone> bones, int repeats) {
        Set<String> present = new HashSet<>();

        for (LiveBone bone : bones) {
            present.add(bone.name().toLowerCase(Locale.ROOT));
        }

        List<LiveBone> out = new ArrayList<>(bones.size());
        int filled = 0;

        for (LiveBone bone : bones) {
            if (bone.parent() != null) {
                out.add(bone);
                continue;
            }

            String above = knownParent(bone.name(), present);

            if (above == null) {
                out.add(bone);
                continue;
            }

            // the name as the model spells it, not as the table does
            String spelled = above;

            for (LiveBone other : bones) {
                if (other.name().equalsIgnoreCase(above)) {
                    spelled = other.name();
                    break;
                }
            }

            out.add(new LiveBone(bone.name(), bone.pivotX(), bone.pivotY(), bone.pivotZ(), spelled));
            filled++;
        }

        int known = 0;

        for (LiveBone bone : out) {
            if (bone.parent() != null) {
                known++;
            }
        }

        com.argorice.epicysm.client.Diag.info("Live skeleton: {} bone(s), {} name(s) seen more than once; the bone above is"
                + " known for {} of them ({} from Yes Steve Model, {} from the names this rig always uses);"
                + " the rest are left to Yes Steve Model to carry",
                bones.size(), repeats, known, known - filled, filled);
        return out;
    }

    @Nullable
    private static String knownParent(String name, Set<String> present) {
        String[] candidates = KNOWN_PARENTS.get(name.toLowerCase(Locale.ROOT));

        if (candidates == null) {
            return null;
        }

        for (String candidate : candidates) {
            if (present.contains(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    /** Map values or collection elements; nothing else is a bone holder. */
    private static List<Object> elementsOf(@Nullable Object held) {
        List<Object> elements = new ArrayList<>();

        try {
            if (held instanceof Map<?, ?> map) {
                for (Object element : map.values()) {
                    if (elements.size() >= 1024) {
                        break;
                    }

                    elements.add(element);
                }
            } else if (held instanceof Collection<?> collection) {
                for (Object element : collection) {
                    if (elements.size() >= 1024) {
                        break;
                    }

                    elements.add(element);
                }
            }
        } catch (Throwable ignored) {
            // Sinytra's redirecting maps throw on iteration; a partial
            // answer is still an answer, and an empty one is handled.
        }

        return elements;
    }

    /**
     * A bone as YSM shapes it: one name, a pivot in three floats, slot and
     * offset ints, and the rest rotation as a Vector3f. Deliberately a
     * shape test rather than a class name, which YSM changes every release.
     */
    private static boolean isBone(Object value) {
        if (!isYsmClass(value.getClass())) {
            return false;
        }

        int strings = 0;
        int floats = 0;
        int ints = 0;
        int vectors = 0;

        for (Class<?> type = value.getClass(); type != null && isYsmClass(type); type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                Class<?> fieldType = field.getType();

                if (fieldType == String.class) {
                    strings++;
                } else if (fieldType == float.class) {
                    floats++;
                } else if (fieldType == int.class) {
                    ints++;
                } else if (fieldType == org.joml.Vector3f.class) {
                    vectors++;
                }
            }
        }

        return strings == 1 && floats >= 3 && ints >= 2 && vectors >= 1;
    }

    /** A bone's own name, whatever it is called. */
    @Nullable
    public static String nameOf(Object bone) {
        for (Class<?> type = bone.getClass(); type != null && isYsmClass(type); type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                    continue;
                }

                try {
                    if (field.trySetAccessible() && field.get(bone) instanceof String name) {
                        return name;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return null;
    }

    /** The first three float fields, in declaration order: the pivot. */
    private static float[] pivotOf(Object bone) {
        float[] pivot = new float[3];
        int at = 0;

        for (Class<?> type = bone.getClass(); type != null && isYsmClass(type) && at < 3; type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != float.class || at >= 3) {
                    continue;
                }

                try {
                    if (field.trySetAccessible()) {
                        pivot[at++] = field.getFloat(bone);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return pivot;
    }

    private static List<Object> seeds(@Nullable AbstractClientPlayer player, Object renderer) {
        List<Object> seeds = new ArrayList<>();
        seeds.add(renderer);

        if (player != null) {
            seeds.add(player);
        }

        ClassLoader loader = renderer.getClass().getClassLoader();
        Set<Class<?>> done = new HashSet<>();

        try {
            for (String className : YsmClasses.names(renderer)) {
                try {
                    Class<?> type = Class.forName(className, false, loader);

                    if (!done.add(type)) {
                        continue;
                    }

                    for (Field field : safeFields(type)) {
                        if (Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()
                                && field.trySetAccessible()) {
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

            if (value.getClass().isArray()) {
                if (!value.getClass().getComponentType().isPrimitive()) {
                    int length = Math.min(Array.getLength(value), 1024);

                    for (int i = 0; i < length; i++) {
                        children.add(Array.get(value, i));
                    }
                }

                return children;
            }

            if (value instanceof String || value instanceof Number || value instanceof Boolean
                    || value instanceof Character || value instanceof Enum<?> || value instanceof Class<?>
                    || value instanceof ResourceLocation) {
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
        }

        return children;
    }

    @Nullable

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
