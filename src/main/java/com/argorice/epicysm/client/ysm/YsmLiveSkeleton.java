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

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

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

    /**
     * What the name of a trunk or head bone contains, whatever a model adds
     * around it (MHead, AllBody, Head2 for a second form): a rig with no
     * such bone is not a body.
     */
    private static final List<String> TRUNK_WORDS = List.of("root", "body", "head");

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
    public record Skeleton(List<LiveBone> bones, List<Object> objects, Object owner, int repeats, Set<String> listed) {
        public Skeleton(List<LiveBone> bones, List<Object> objects, Object owner) {
            this(bones, objects, owner, 0, Set.of());
        }

        public Skeleton(List<LiveBone> bones, List<Object> objects, Object owner, int repeats) {
            this(bones, objects, owner, repeats, Set.of());
        }

        /**
         * Whether this is a body at all: a bone the trunk or the head is
         * made of. Yes Steve Model's model for the first-person view is
         * arms alone - LeftArm, LeftForeArm, a hand locator, and the same
         * on the right - and it hangs beside the body, under the same
         * texture, once anything has drawn the hands in first person.
         */
        public boolean hasTrunk() {
            for (LiveBone bone : this.bones) {
                String name = bone.name().toLowerCase(Locale.ROOT);

                for (String word : TRUNK_WORDS) {
                    if (name.contains(word)) {
                        return true;
                    }
                }
            }

            return false;
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
    public static List<Skeleton> read(@Nullable LivingEntity player, Object renderer) {
        try {
            Found found = walk(player, renderer, null);
            sayIfSlow(found, null);
            return found.skeletons;
        } catch (Throwable t) {
            EpicYsm.LOGGER.debug("Could not read Yes Steve Model's live skeleton", t);
            return List.of();
        }
    }

    /** The skeleton of the model being drawn with this texture. */
    @Nullable
    public static Skeleton readFor(@Nullable LivingEntity player, Object renderer,
                                   @Nullable ResourceLocation texture) {
        return readFor(player, renderer, texture, Set.of());
    }

    /**
     * @param rejected model objects already tried and found not to be the
     *                 one Yes Steve Model animates (a cached copy with the
     *                 same bones); they are passed over
     */
    @Nullable
    public static Skeleton readFor(@Nullable LivingEntity player, Object renderer,
                                   @Nullable ResourceLocation texture, Set<Object> rejected) {
        try {
            Found found = walk(player, renderer, texture);

            if (found.skeletons.isEmpty()) {
                sayIfSlow(found, null);
                return null;
            }

            // Yes Steve Model can hold the same model twice: the copy in its
            // cache and the copy built for this player. Only the second is
            // animated. The one that hangs below the object holding the
            // player's texture (or the player himself) is preferred; a plain
            // nearest-common-ancestor distance breaks ties after that. Before
            // any of that, a rig that is a body at all: the first-person arms
            // hang just as close to the texture as the body does, and with
            // shaders on they are there in the third person too.
            List<Object> holders = new ArrayList<>(found.textureHolders);
            holders.addAll(found.playerHolders);
            Skeleton best = null;
            int bestBelow = Integer.MAX_VALUE;
            int bestBelowEntity = Integer.MAX_VALUE;
            int bestDistance = Integer.MAX_VALUE;
            boolean bestIsOneRig = false;
            boolean bestHasTrunk = false;
            boolean bestIsOthersBody = false;
            int copies = 0;
            int armsAlone = 0;
            int othersBodies = 0;

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

                // Below the object holding this very entity, before anything
                // holding the texture: two bodies in the same model - a
                // player and a maid dressed alike - share the texture, and
                // each hangs under its own entity's holder.
                int belowEntity = Integer.MAX_VALUE;

                for (Object holder : found.playerHolders) {
                    int down = stepsBelow(found.parents, holder, skeleton.owner());

                    if (down >= 0 && down < belowEntity) {
                        belowEntity = down;
                    }
                }

                // And below the record of some other body - another player,
                // a maid - in the same model: that copy is theirs. It is the
                // last choice, and it is not taken at all: written to from
                // here as well as from its own overlay, it came apart.
                int belowOther = Integer.MAX_VALUE;

                for (Object holder : found.otherHolders) {
                    int down = stepsBelow(found.parents, holder, skeleton.owner());

                    if (down >= 0 && down < belowOther) {
                        belowOther = down;
                    }
                }

                boolean othersBody = belowOther != Integer.MAX_VALUE && belowOther < belowEntity;
                copies++;

                // A rig has one bone called LeftArm. Something that has
                // seven of them is not a model, it is every model Yes
                boolean oneRig = skeleton.repeats() == 0;
                boolean hasTrunk = skeleton.hasTrunk();
                boolean better;

                if (!hasTrunk) {
                    armsAlone++;
                }

                if (othersBody) {
                    othersBodies++;
                }

                if (hasTrunk != bestHasTrunk) {
                    better = hasTrunk;
                } else if (oneRig != bestIsOneRig) {
                    better = oneRig;
                } else if (othersBody != bestIsOthersBody) {
                    better = !othersBody;
                } else if (belowEntity != bestBelowEntity) {
                    better = belowEntity < bestBelowEntity;
                } else if (below != bestBelow) {
                    better = below < bestBelow;
                } else {
                    better = distance < bestDistance;
                }

                if (better) {
                    bestBelow = below;
                    bestBelowEntity = belowEntity;
                    bestDistance = distance;
                    bestIsOneRig = oneRig;
                    bestHasTrunk = hasTrunk;
                    bestIsOthersBody = othersBody;
                    best = skeleton;
                }
            }

            sayIfSlow(found, best);

            if (best != null && bestIsOthersBody) {
                com.argorice.epicysm.client.Diag.info("Live skeleton: the only copy of this model left to take for {} hangs below"
                        + " Yes Steve Model's record of another body ({} candidate(s), {} passed over); it is that body's"
                        + " and is left alone", player == null ? "?" : YsmSkeletonOverlay.nameOf(player), copies, rejected.size());
                return null;
            }

            if (best != null) {
                com.argorice.epicysm.client.Diag.info("Live skeleton chosen for this model on {}: {} bone(s), {} repeated name(s),"
                        + " {} step(s) from the object holding the texture, {} below it, {} below the object holding the"
                        + " entity; {} candidate(s), {} passed over, {} of them arms alone, {} another body's",
                        player == null ? "?" : YsmSkeletonOverlay.nameOf(player), best.bones().size(), best.repeats(),
                        bestDistance, bestBelow == Integer.MAX_VALUE ? "not" : bestBelow,
                        bestBelowEntity == Integer.MAX_VALUE ? "not" : bestBelowEntity, copies, rejected.size(), armsAlone,
                        othersBodies);
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
        /** Objects of Yes Steve Model's that hold some other living entity: another body's. */
        final List<Object> otherHolders = new ArrayList<>();
        final Map<Object, Object> parents = new IdentityHashMap<>(MAX_NODES);
        /** For each skeleton's owner: after how many objects it came up, and how deep. */
        final Map<Object, int[]> cameUp = new IdentityHashMap<>();
        final Map<Class<?>, int[]> kinds = new IdentityHashMap<>();
        long nanos;
        int nodes;
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

    private static Found walk(@Nullable LivingEntity player, Object renderer,
                              @Nullable ResourceLocation texture) {
        // What is put in line is written down with the object it was found
        // in, the starting points apart, so that nothing is put in twice; a
        // depth is told by counting off one round of the line after another.
        // Nothing is put in line past the most a look reaches: the line is
        // gone through in order, and what stands past that place is never
        // reached. Kept of everything found, the line and the maps beside it
        // grew to millions of objects and were most of a look's time.
        Found found = new Found();
        Map<Object, Object> parents = found.parents;
        Set<Object> starts = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> queue = new ArrayDeque<>(1 << 12);
        Set<Object> seenOwners = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        for (Object seed : seeds(player, renderer)) {
            if (seed != null && starts.add(seed)) {
                queue.add(seed);
            }
        }

        int nodes = 0;
        int depth = 0;
        int queued = queue.size();
        int leftThisRound = queued;
        int nextRound = 0;
        long started = System.nanoTime();
        Map<Class<?>, int[]> kinds = found.kinds;
        Class<?> lastType = null;
        int[] lastCount = null;

        while (!queue.isEmpty() && nodes < MAX_NODES) {
            if (leftThisRound == 0) {
                depth++;
                leftThisRound = nextRound;
                nextRound = 0;
            }

            Object value = queue.poll();
            leftThisRound--;
            nodes++;
            Class<?> type = value.getClass();
            Kind kind = KINDS.get(type);

            // Counted for the line in the log; objects of a kind come in runs.
            if (type != lastType) {
                lastType = type;
                lastCount = kinds.computeIfAbsent(type, unused -> new int[1]);
            }

            lastCount[0]++;

            if (queued < MAX_NODES && depth < MAX_DEPTH && (value == player || value == renderer || kind.descends())) {
                for (Object child : childrenOf(value, kind)) {
                    if (child != null && !KINDS.get(child.getClass()).leaf() && !starts.contains(child)
                            && parents.putIfAbsent(child, value) == null) {
                        queue.add(child);
                        nextRound++;

                        if (++queued >= MAX_NODES) {
                            break;
                        }
                    }
                }
            }

            if (!kind.ysm()) {
                continue;
            }

            if (texture != null && found.textureHolders.size() < 16 && holdsTexture(value, kind, texture)) {
                found.textureHolders.add(value);
            }

            if (player != null && found.playerHolders.size() < 32 && holdsPlayer(value, kind, player)) {
                found.playerHolders.add(value);
            } else if (player != null && found.otherHolders.size() < 64 && holdsOtherLiving(value, kind, player)) {
                found.otherHolders.add(value);
            }

            Skeleton skeleton = skeletonOf(value, kind);

            // The same model object is reachable by several routes; a second
            // object with the same bones is a second copy and is kept.
            if (skeleton != null && seenOwners.add(skeleton.objects().isEmpty() ? skeleton.owner() : skeleton.objects().get(0))) {
                found.skeletons.add(skeleton);
                found.cameUp.put(skeleton.owner(), new int[] {nodes, depth});
            }
        }

        found.nanos = System.nanoTime() - started;
        found.nodes = nodes;
        return found;
    }

    private static int saidSlow;

    /**
     * The first eight times a session a look through Yes Steve Model's memory
     * took long enough to be felt: how long, over how many objects, how far in
     * the skeleton taken came up, and whose objects most of them were - what a
     * narrower or a shorter look would have to leave out.
     */
    private static void sayIfSlow(Found found, @Nullable Skeleton taken) {
        if (saidSlow >= 8 || found.nanos < 100_000_000L) {
            return;
        }

        saidSlow++;
        int[] takenAt = taken == null ? null : found.cameUp.get(taken.owner());
        int first = Integer.MAX_VALUE;

        for (int[] at : found.cameUp.values()) {
            first = Math.min(first, at[0]);
        }

        String cameUp = takenAt != null
                ? String.format(Locale.ROOT, "the skeleton taken came up %d object(s) in, %d step(s) deep, out of %d found",
                        takenAt[0], takenAt[1], found.skeletons.size())
                : found.skeletons.isEmpty() ? "no skeleton came up"
                : String.format(Locale.ROOT, "%d skeleton(s) came up, the first %d object(s) in", found.skeletons.size(), first);
        Map<String, Integer> byPackage = new java.util.HashMap<>();

        for (Map.Entry<Class<?>, int[]> entry : found.kinds.entrySet()) {
            String name = entry.getKey().getName();
            String[] parts = name.split("\\.");
            String where = parts.length <= 3 ? name : parts[0] + "." + parts[1] + "." + parts[2];
            byPackage.merge(where, entry.getValue()[0], Integer::sum);
        }

        String most = byPackage.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(12)
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        EpicYsm.LOGGER.info("Live skeleton: looking through Yes Steve Model's memory took {} ms over {} object(s){}; {}; most of them: {}",
                found.nanos / 1_000_000L, found.nodes, found.nodes >= MAX_NODES ? " (as many as it looks at)" : "", cameUp, most);
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

            Kind kind = KINDS.get(value.getClass());

            if (kind.ysm() && holdsSize(value)) {
                nearest = value;
                nearestDepth = depth;
                continue;
            }

            if (depth < 6 && kind.descends()) {
                for (Object child : childrenOf(value, kind)) {
                    if (child != null && !KINDS.get(child.getClass()).leaf() && !visited.contains(child)
                            && depths.putIfAbsent(child, depth + 1) == null) {
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

    /**
     * Whether one of this object's own fields, of a kind that could hold
     * this entity, holds some other living entity instead: the record Yes
     * Steve Model keeps of another body, whose model hangs below it.
     */
    private static boolean holdsOtherLiving(Object value, Kind kind, Object player) {
        for (Field field : kind.entityFields()) {
            if (!field.getType().isInstance(player)) {
                continue;
            }

            try {
                Object held = field.get(value);

                if (held instanceof LivingEntity && held != player) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }

        return false;
    }

    /** Whether one of this object's own fields is this very player. */
    private static boolean holdsPlayer(Object value, Kind kind, Object player) {
        for (Field field : kind.entityFields()) {
            if (!field.getType().isInstance(player)) {
                continue;
            }

            try {
                if (field.get(value) == player) {
                    return true;
                }
            } catch (Throwable ignored) {
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
    private static boolean holdsTexture(Object value, Kind kind, ResourceLocation texture) {
        for (Field field : kind.ownReferences()) {
            try {
                if (texture.equals(field.get(value))) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }

        return false;
    }

    /**
     * The skeleton this object owns, if it owns one: a map or list holding
     * bone objects, plus the root-to-leaf chains beside it.
     */
    @Nullable
    private static Skeleton skeletonOf(Object model, Kind kind) {
        // Made only once a bone turns up: nearly every object looked at owns
        // none, and a look goes through a couple of hundred thousand.
        Map<Object, String> names = null;
        List<Object> ordered = null;
        List<List<Object>> chains = null;

        for (Field field : kind.containers()) {
            Object held;

            try {
                held = field.get(model);
            } catch (Throwable ignored) {
                continue;
            }

            if (!(held instanceof Map<?, ?>) && !(held instanceof Collection<?>)) {
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
                    if (!(element instanceof Map<?, ?>) && !(element instanceof Collection<?>)) {
                        continue;
                    }

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
                        if (chains == null) {
                            names = new IdentityHashMap<>();
                            ordered = new ArrayList<>();
                            chains = new ArrayList<>();
                        }

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

            // Bones every one, and as many as the look takes: then there
            // are more. The look stops at a thousand, enough to tell what
            // a collection holds and not enough to hold a large model.
            // One of seventeen hundred bones came out a thousand strong,
            // a different thousand every time the game was started, and
            // what was left out - a thigh, a head, a hand's locator -
            // was neither posed nor held.
            if (elements.size() >= PEEK) {
                bones = everyBoneOf(held);
            }

            if (chains == null) {
                names = new IdentityHashMap<>();
                ordered = new ArrayList<>();
                chains = new ArrayList<>();
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

        if (chains == null) {
            return null;
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
        // The bones whose bone above is Yes Steve Model's own word, not a
        // guess from the name.
        Set<String> listed = new HashSet<>();
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

            if (parent != null && names.get(parent) != null) {
                listed.add(name);
            }
        }

        List<LiveBone> whole = fillParents(bones, repeats);

        return whole.size() < MIN_BONES ? null
                : new Skeleton(List.copyOf(whole), List.copyOf(objects), model, repeats, Set.copyOf(listed));
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
        KNOWN_PARENTS.put("hhead", new String[] { "allhead", "upperbody", "mupperbody", "upbody" });
        KNOWN_PARENTS.put("head", new String[] { "mhead", "hhead", "allhead" });
        KNOWN_PARENTS.put("hair", new String[] { "head", "mhead", "hhead", "allhead" });
        KNOWN_PARENTS.put("face", new String[] { "head", "mhead", "hhead", "allhead" });
        KNOWN_PARENTS.put("leg", new String[] { "downbody", "allbody" });
        KNOWN_PARENTS.put("leftleg", new String[] { "leg", "downbody", "allbody", "mallbody", "root" });
        KNOWN_PARENTS.put("rightleg", new String[] { "leg", "downbody", "allbody", "mallbody", "root" });
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
    /** How much of a collection is looked at to see what it holds. */
    private static final int PEEK = 1024;

    /** More bones than any model has; a collection is not read past it. */
    private static final int MOST_BONES = 65536;

    /** Every bone of a collection already seen to hold nothing else. */
    private static List<Object> everyBoneOf(@Nullable Object held) {
        List<Object> bones = new ArrayList<>();

        try {
            Collection<?> all = held instanceof Map<?, ?> map ? map.values()
                    : held instanceof Collection<?> collection ? collection : List.of();

            for (Object element : all) {
                if (bones.size() >= MOST_BONES) {
                    break;
                }

                if (element != null && isBone(element)) {
                    bones.add(element);
                }
            }
        } catch (Throwable ignored) {
            // As far as it could be read.
        }

        return bones;
    }

    private static List<Object> elementsOf(@Nullable Object held) {
        List<Object> elements = new ArrayList<>();

        try {
            if (held instanceof Map<?, ?> map) {
                for (Object element : map.values()) {
                    if (elements.size() >= PEEK) {
                        break;
                    }

                    elements.add(element);
                }
            } else if (held instanceof Collection<?> collection) {
                for (Object element : collection) {
                    if (elements.size() >= PEEK) {
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
     * The shape is the class's, so it is worked out once for each class.
     */
    private static boolean isBone(Object value) {
        return KINDS.get(value.getClass()).bone();
    }

    private static boolean isBoneShaped(Class<?> kind) {
        if (!isYsmClass(kind)) {
            return false;
        }

        int strings = 0;
        int floats = 0;
        int ints = 0;
        int vectors = 0;

        for (Class<?> type = kind; type != null && isYsmClass(type); type = type.getSuperclass()) {
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

    private static List<Object> seeds(@Nullable LivingEntity player, Object renderer) {
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

    private static List<Object> childrenOf(Object value, Kind kind) {
        List<Object> children = new ArrayList<>();

        try {
            if (kind.plain() && value instanceof Map<?, ?> map) {
                for (Object element : map.values()) {
                    if (children.size() >= 1024) {
                        break;
                    }

                    children.add(element);
                }

                return children;
            }

            if (kind.plain() && value instanceof Collection<?> collection) {
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

            if (kind.leaf()) {
                return children;
            }

            for (Field field : kind.references()) {
                try {
                    children.add(field.get(value));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        return children;
    }

    /**
     * A class's own fields, made accessible where they can be, looked up once
     * and kept. Asked anew for every object, the lookup copied the array and
     * checked access field by field, on every one of the objects a look goes
     * through.
     */
    private static final ClassValue<Field[]> FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            Field[] fields;

            try {
                fields = type.getDeclaredFields();
            } catch (Throwable t) {
                return new Field[0];
            }

            for (Field field : fields) {
                try {
                    field.trySetAccessible();
                } catch (Throwable ignored) {
                    // Left as it is; the readers ask again and pass it over.
                }
            }

            return fields;
        }
    };

    private static Field[] safeFields(Class<?> type) {
        return FIELDS.get(type);
    }

    private static boolean isYsmClass(Class<?> type) {
        return type != null && type.getName().startsWith(YSM_PACKAGE);
    }

    /**
     * What a look needs to know of a class, worked out once for each. Of the
     * 200 000 objects a look goes through, nearly all are of a few dozen
     * classes, and asked anew for every object the same answers took most of
     * a look's time: the fields listed and checked again, and every
     * element of every collection sized up as a bone field by field.
     *
     * @param references    the fields an object of the class holds another
     *                      object in, its own and its superclasses', that
     *                      can be read
     * @param ownReferences the same, from the classes of Yes Steve Model's
     *                      alone, from the class up to the first that is not
     * @param containers    those of the own that can hold a map or a collection
     * @param entityFields  those of all that can hold a living entity
     */
    private record Kind(boolean ysm, boolean leaf, boolean descends, boolean plain, boolean bone,
                        Field[] references, Field[] ownReferences, Field[] containers, Field[] entityFields) {
    }

    private static final ClassValue<Kind> KINDS = new ClassValue<>() {
        @Override
        protected Kind computeValue(Class<?> type) {
            return kindOf(type);
        }
    };

    private static Kind kindOf(Class<?> type) {
        String name = type.getName();
        boolean ysm = name.startsWith(YSM_PACKAGE);
        List<Field> references = new ArrayList<>();
        List<Field> own = new ArrayList<>();
        List<Field> containers = new ArrayList<>();
        List<Field> entities = new ArrayList<>();

        if (!type.isArray()) {
            boolean stillOwn = ysm;

            for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
                stillOwn = stillOwn && isYsmClass(at);

                for (Field field : safeFields(at)) {
                    Class<?> held = field.getType();

                    if (Modifier.isStatic(field.getModifiers()) || held.isPrimitive()) {
                        continue;
                    }

                    boolean readable;

                    try {
                        readable = field.trySetAccessible();
                    } catch (Throwable t) {
                        readable = false;
                    }

                    if (!readable) {
                        continue;
                    }

                    references.add(field);

                    if (held.isInterface() || held.isAssignableFrom(LivingEntity.class) || LivingEntity.class.isAssignableFrom(held)) {
                        entities.add(field);
                    }

                    if (stillOwn) {
                        own.add(field);

                        if (!held.isArray() && (Map.class.isAssignableFrom(held) || Collection.class.isAssignableFrom(held)
                                || held.isInterface() || !Modifier.isFinal(held.getModifiers()))) {
                            containers.add(field);
                        }
                    }
                }
            }
        }

        return new Kind(ysm, isLeafKind(type), descendsInto(type, name), readsPlainly(name), isBoneShaped(type),
                references.toArray(new Field[0]), own.toArray(new Field[0]), containers.toArray(new Field[0]),
                entities.toArray(new Field[0]));
    }

    /**
     * Values that hold nothing to look through: text, numbers and the like,
     * and arrays of plain numbers. They are not counted against the most a
     * look goes through - of 200 000 objects, 143 278 were strings, and the
     * look stopped there.
     */
    private static boolean isLeafKind(Class<?> type) {
        return String.class.isAssignableFrom(type) || Number.class.isAssignableFrom(type) || type == Boolean.class
                || type == Character.class || Enum.class.isAssignableFrom(type) || type == Class.class
                || ResourceLocation.class.isAssignableFrom(type)
                || (type.isArray() && type.getComponentType().isPrimitive());
    }

    static boolean descendsInto(Object value) {
        return KINDS.get(value.getClass()).descends();
    }

    /**
     * Whether the look goes on through what an object holds. The game's own
     * objects are passed over, but for the player and the renderer the
     * look starts from: through them it went into the world - its chunks,
     * entities, registries, the server - and of 200 000 objects a look went
     * through in a large pack, 8 209 were Yes Steve Model's and 150 000 and
     * more the game's, a second of a frozen game at every change of model,
     * with the look stopped at its limit before it had seen everything. Yes
     * Steve Model keeps what this looks for in its own objects. The holders
     * a mod's data rides on an entity by (NeoForge's attachments, Forge's
     * capabilities) are gone through, the way from the player to Yes Steve
     * Model's record of him.
     */
    private static boolean descendsInto(Class<?> type, String name) {
        if (name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")) {
            return false;
        }

        // Logging, threads, class loaders, reflection: what every mod holds
        // and nothing of a model is kept in. Yes Steve Model's loggers led
        // into every logger of the game.
        if (name.startsWith("org.apache.logging.") || name.startsWith("org.slf4j.") || name.startsWith("java.lang.Thread")
                || name.startsWith("java.lang.invoke.") || name.startsWith("java.lang.reflect.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("cpw.mods.") || name.startsWith("org.spongepowered.")
                || ClassLoader.class.isAssignableFrom(type)) {
            return false;
        }

        if (name.startsWith("net.neoforged.") || name.startsWith("net.minecraftforge.")) {
            return name.startsWith("net.neoforged.neoforge.attachment.") || name.startsWith("net.minecraftforge.common.capabilities.");
        }

        return true;
    }

    static boolean readsPlainly(Object value) {
        return KINDS.get(value.getClass()).plain();
    }

    /**
     * Whether a map or a collection is gone through by its elements: Java's
     * own, fastutil's, Guava's stored ones, the caches of Guava and
     * Caffeine, and Yes Steve Model's. Any other - another mod's lazy map,
     * a view that makes each value as it is read - is gone through by its
     * fields, like any other object, which reads what it holds and makes
     * nothing. ModernFix, with its dynamic resources on, keeps the game's
     * block models in such a map and loads one for every value read out of
     * it: a walk that went through it loaded them all, a freeze of seconds
     * at every change of model.
     */
    private static boolean readsPlainly(String name) {
        if (name.startsWith(YSM_PACKAGE) || name.startsWith("it.unimi.dsi.fastutil.")) {
            return true;
        }

        if (name.startsWith("java.util.")) {
            // Java's wrappers and the views of a map Java does not know go
            // through to whatever they hold.
            return !(name.contains("$Unmodifiable") || name.contains("$Synchronized") || name.contains("$Checked")
                    || name.contains("$SetFromMap") || name.startsWith("java.util.AbstractMap$"));
        }

        if (name.startsWith("com.google.common.collect.")) {
            String kind = name.substring("com.google.common.collect.".length());
            return kind.startsWith("Immutable") || kind.startsWith("Regular") || kind.startsWith("Singleton")
                    || kind.startsWith("JdkBacked") || kind.startsWith("Hash") || kind.startsWith("Linked")
                    || kind.startsWith("ArrayList") || kind.startsWith("Tree") || kind.startsWith("Enum")
                    || kind.startsWith("Concurrent") || kind.startsWith("Compact") || kind.startsWith("Empty");
        }

        return name.startsWith("com.google.common.cache.LocalCache") || name.startsWith("com.github.benmanes.caffeine.cache.");
    }
}
