package com.argorice.epicysm.client.ysm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * The body on screen, put together bone by bone.
 *
 * A model with more than one body marks the bones of the second like the
 * first with a number on them, and the mark was taken for the body: every
 * bone marked 3 one body, every plain one another. Models are not built
 * that tidily. One measured keeps a small body beside a big one, twenty-five
 * pixels to the side, and moves whichever is in use to the middle; the small
 * one has the plain names from the waist to the hands and to the feet, the
 * big one's head is plain as well, and what is left of either is marked 2,
 * 3 or 4 with no pattern to it. Read by the mark, the small body was posed
 * with the head of the big one, and the skeleton built for it had its chest
 * twenty-five pixels from its hips.
 *
 * So after the mark has had its say the body is looked at where it stands:
 * a head or a trunk bone far from the trunk is another body's and is left
 * alone, a part the body lacks is taken from the bones of that name that
 * stand with it whatever their mark, and a limb is posed only as far down
 * as it is whole on both sides.
 *
 * One more thing is known of every bone: its place in the model's own
 * list, which runs parent first and everything under it after - checked on
 * the models whose files can be read, where it holds for every bone. So a
 * bone listed between another and one known to hang from that other hangs
 * from it too. A doll the small body holds has a waist and legs of its
 * own, two pixels from the body's middle; they are listed between the hand
 * and the hand's locator, and so are the hand's, not the body's.
 *
 * Nothing here reads Yes Steve Model's objects; it works on names, places,
 * that order and whether a bone is drawn.
 */
final class BodySettler {
    /**
     * One bone: its name, where its pivot is, whether it is drawn, the bone
     * above it when that is known, and its place in the model's own list
     * (-1 when that is not known).
     */
    record Part(String name, float x, float y, float z, boolean shown, @Nullable String above, int order) {
    }

    /** What became of the body. */
    static final class Settled {
        /** Bones that are another body's, by the model's own name; left alone. */
        final Set<String> foreign;
        /** The name the solver is to know a bone by, where that is not the name the mark alone gives. */
        final Map<String, String> renames = new LinkedHashMap<>();
        /** Where the body stands, off the model's middle; nought when it stands on it. */
        float x;
        float z;
        final List<String> notes = new ArrayList<>();

        Settled(Set<String> foreign) {
            this.foreign = foreign;
        }

        boolean changed() {
            return !this.notes.isEmpty();
        }
    }

    private static final Set<String> TRUNK = Set.of("root", "mroot", "allbody", "mallbody", "downbody",
            "upbody", "mupbody", "upperbody", "mupperbody");
    private static final Set<String> HEADS = Set.of("allhead", "mhead", "hhead", "head");
    /** Names of the middle of a body that carry no joint of their own. */
    private static final Set<String> GROUPS = Set.of("arm", "leg");
    private static final String LOCATOR = "handlocator";
    /** Down an arm, to the bone the held item hangs on. */
    private static final String[] ARM = { "arm", "forearm", "hand", LOCATOR };
    /** How far from its hand a hand's locator may rest. */
    private static final float HAND_REACH = 10.0F;
    private static final String[] LEG = { "leg", "lowerleg", "foot" };
    private static final String[] SIDES = { "left", "right" };

    /** A body further than this from the model's middle is one the model parks there and brings in. */
    private static final float PARKED = 6.0F;
    /** How alike in height and in distance from the middle the two bones of a pair must be. */
    private static final float PAIR = 3.0F;

    /**
     * The names another rig goes by - hips, a spine, a chest above it, a
     * neck, a lower arm - and the name this mod knows the same part by.
     * Used only where the body has no bone of the usual name.
     */
    private static final String[][] OTHER_RIG = {
        { "hip", "Root" }, { "hips", "Root" }, { "spine", "UpBody" }, { "neck", "AllHead" },
        { "leftlowerarm", "LeftForeArm" }, { "rightlowerarm", "RightForeArm" },
    };

    private BodySettler() {
    }

    /**
     * @param parts every bone of the model
     * @param chosen the mark of the body on screen, "" for the plain one
     * @param foreignByName the bones the mark alone gives to another body; not changed
     */
    static Settled settle(List<Part> parts, String chosen, Set<String> foreignByName) {
        Settled out = new Settled(new HashSet<>(foreignByName));
        Map<String, Part> byName = new HashMap<>();

        for (Part part : parts) {
            byName.putIfAbsent(part.name(), part);
        }

        Map<String, Integer> ends = ends(parts, byName);

        // What the body has, by the plain name of each part.
        Map<String, Part> have = new LinkedHashMap<>();

        for (Part part : parts) {
            if (!out.foreign.contains(part.name()) && YsmSkeletonOverlay.formKey(part.name()).equals(chosen)) {
                String base = base(part.name());

                if (named(base)) {
                    have.putIfAbsent(base, part);
                }
            }
        }

        otherRig(parts, have, out);

        List<Part> trunk = new ArrayList<>();

        for (Map.Entry<String, Part> entry : have.entrySet()) {
            if (TRUNK.contains(entry.getKey())) {
                trunk.add(entry.getValue());
            }
        }

        if (trunk.isEmpty()) {
            return out;
        }

        float middleX = median(trunk, true);
        float middleZ = median(trunk, false);
        float low = Float.MAX_VALUE;
        float high = -Float.MAX_VALUE;

        for (Map.Entry<String, Part> entry : have.entrySet()) {
            if (!HEADS.contains(entry.getKey())) {
                low = Math.min(low, entry.getValue().y());
                high = Math.max(high, entry.getValue().y());
            }
        }

        float height = Math.max(8.0F, high - low);
        float reach = Math.max(8.0F, height * 0.3F);
        float armReach = Math.max(reach, height * 0.75F);

        // A head or a trunk bone that stands somewhere else is not this body's.
        for (Map.Entry<String, Part> entry : new ArrayList<>(have.entrySet())) {
            Part part = entry.getValue();
            String base = entry.getKey();

            // Not the root: a body built off the model's middle still
            // hangs from a root that sits on it.
            if ((TRUNK.contains(base) || HEADS.contains(base) || GROUPS.contains(base))
                    && !base.equals("root") && !base.equals("mroot") && aside(part, middleX, middleZ) > reach) {
                elsewhere(base, have, out, middleX, middleZ);
            }
        }

        // Nor is a limb: its two sides stand either side of the trunk, and
        // a pair whose middle is somewhere else hangs from another trunk.
        // One with no fellow is judged by how far out it is.
        float offMiddle = Math.max(4.0F, height * 0.15F);

        for (String[] chain : new String[][] { ARM, LEG }) {
            for (String level : chain) {
                Part left = have.get(SIDES[0] + level);
                Part right = have.get(SIDES[1] + level);

                if (left != null && right != null) {
                    if (Math.abs((left.x() + right.x()) * 0.5F - middleX) > offMiddle) {
                        elsewhere(SIDES[0] + level, have, out, middleX, middleZ);
                        elsewhere(SIDES[1] + level, have, out, middleX, middleZ);
                    }
                } else if (left != null || right != null) {
                    Part one = left != null ? left : right;

                    if (aside(one, middleX, middleZ) > (chain == ARM ? armReach : reach)) {
                        elsewhere(SIDES[left != null ? 0 : 1] + level, have, out, middleX, middleZ);
                    }
                }
            }
        }

        // What the body lacks, from the bones of that name standing with it.
        Map<String, List<Part>> spare = new HashMap<>();

        for (Part part : parts) {
            if (foreignByName.contains(part.name()) && part.shown()) {
                String base = base(part.name());

                if (named(base) && !base.endsWith(LOCATOR) && !have.containsKey(base)
                        && !onAnotherLimb(part, base, have, ends)) {
                    // Arms may be modelled spread wide; nothing else is.
                    float limit = partOf(ARM, base) ? armReach : reach;

                    if (aside(part, middleX, middleZ) <= limit) {
                        spare.computeIfAbsent(base, key -> new ArrayList<>()).add(part);
                    }
                }
            }
        }

        for (Map.Entry<String, List<Part>> entry : spare.entrySet()) {
            String base = entry.getKey();

            if (!TRUNK.contains(base) && !HEADS.contains(base) && !GROUPS.contains(base)) {
                continue;
            }

            Part best = null;

            for (Part part : entry.getValue()) {
                if (best == null || aside(part, middleX, middleZ) < aside(best, middleX, middleZ)) {
                    best = part;
                }
            }

            take(best, base, have, out);
        }

        for (String[] chain : new String[][] { ARM, LEG }) {
            for (String level : chain) {
                pair(level, have, spare, middleX, out);
            }
        }

        // The bone a held item hangs on goes with its hand, drawn or not:
        // a model that shows a weapon of its own keeps it scaled to nothing.
        for (String side : SIDES) {
            Part hand = have.containsKey(side + "hand") ? have.get(side + "hand") : have.get(side + "forearm");

            if (hand == null || have.containsKey(side + LOCATOR)) {
                continue;
            }

            // The one Yes Steve Model itself lists under this hand before
            // any other: a doll in the hand has locators of its own, and
            // one of them may rest nearer the hand than the hand's.
            Part best = null;
            boolean bestListed = false;

            for (Part part : parts) {
                if (!foreignByName.contains(part.name()) || !base(part.name()).equals(side + LOCATOR)
                        || onAnotherLimb(part, side + LOCATOR, have, ends) || apart(part, hand) > HAND_REACH) {
                    continue;
                }

                boolean listed = part.above() != null && part.above().equals(hand.name());

                if (best == null || listed && !bestListed || listed == bestListed && apart(part, hand) < apart(best, hand)) {
                    best = part;
                    bestListed = listed;
                }
            }

            take(best, side + LOCATOR, have, out);
        }

        // Whatever else of that kind stands with the body belongs to it too,
        // and is held with it rather than left to play on its own.
        for (List<Part> list : spare.values()) {
            for (Part part : list) {
                if (out.foreign.remove(part.name())) {
                    out.renames.put(part.name(), part.name());
                    out.notes.add(part.name() + " stands with the body and is held with it");
                }
            }
        }

        if (out.changed()) {
            whole(have, out);
        }

        if (Math.hypot(middleX, middleZ) > PARKED) {
            out.x = Math.abs(middleX) < 1.0F ? 0.0F : middleX;
            out.z = Math.abs(middleZ) < 1.0F ? 0.0F : middleZ;
            out.notes.add("the body stands " + Math.round(out.x * 10.0F) / 10.0F + " px sideways and "
                    + Math.round(out.z * 10.0F) / 10.0F + " px along from the model's middle");
        }

        return out;
    }

    /** Another rig's names, where the body has none of the usual trunk. */
    private static void otherRig(List<Part> parts, Map<String, Part> have, Settled out) {
        for (String usual : new String[] { "allbody", "mallbody", "upbody", "mupbody", "upperbody", "mupperbody" }) {
            if (have.containsKey(usual)) {
                return;
            }
        }

        Map<String, Part> loose = new HashMap<>();

        for (Part part : parts) {
            if (!out.foreign.contains(part.name())) {
                loose.putIfAbsent(part.name().toLowerCase(Locale.ROOT), part);
            }
        }

        for (String[] entry : OTHER_RIG) {
            Part part = loose.get(entry[0]);
            String as = entry[1].toLowerCase(Locale.ROOT);

            if (part != null && !have.containsKey(as) && !heads(as, have)) {
                rename(part, entry[1], have, out);
            }
        }

        // One chest or two: the upper one carries the arms and the head.
        Part chest = loose.get("chest");
        Part upper = loose.get("upperchest");

        if (chest != null && upper != null) {
            rename(chest, "MUpperBody", have, out);
            rename(upper, "UpperBody", have, out);
        } else if (chest != null || upper != null) {
            rename(chest != null ? chest : upper, "UpperBody", have, out);
        }
    }

    /** Whether the body already has a bone the head turns on, when a neck is offered for one. */
    private static boolean heads(String as, Map<String, Part> have) {
        return as.equals("allhead") && (have.containsKey("mhead") || have.containsKey("hhead"));
    }

    private static void rename(Part part, String as, Map<String, Part> have, Settled out) {
        have.put(as.toLowerCase(Locale.ROOT), part);
        out.renames.put(part.name(), as);
        out.notes.add(part.name() + " is this rig's " + as);
    }

    private static void elsewhere(String base, Map<String, Part> have, Settled out, float middleX, float middleZ) {
        Part part = have.remove(base);

        if (part != null) {
            out.foreign.add(part.name());
            out.notes.add(part.name() + " stands " + Math.round(aside(part, middleX, middleZ))
                    + " px from the trunk and is another body's");
        }
    }

    private static void take(@Nullable Part part, String base, Map<String, Part> have, Settled out) {
        if (part == null) {
            return;
        }

        have.put(base, part);
        out.foreign.remove(part.name());
        out.renames.put(part.name(), YsmSkeletonOverlay.baseName(part.name()));
        out.notes.add(part.name() + " is the body's " + YsmSkeletonOverlay.baseName(part.name()));
    }

    /** A limb's bone is taken with its fellow on the other side, or not at all. */
    private static void pair(String level, Map<String, Part> have, Map<String, List<Part>> spare, float middleX, Settled out) {
        String leftName = SIDES[0] + level;
        String rightName = SIDES[1] + level;
        Part left = have.get(leftName);
        Part right = have.get(rightName);

        if (left != null && right != null) {
            return;
        }

        List<Part> lefts = left != null ? List.of(left) : spare.getOrDefault(leftName, List.of());
        List<Part> rights = right != null ? List.of(right) : spare.getOrDefault(rightName, List.of());
        Part bestLeft = null;
        Part bestRight = null;
        float bestApart = PAIR;

        for (Part one : lefts) {
            for (Part other : rights) {
                float apart = Math.max(Math.abs(one.y() - other.y()),
                        Math.abs(Math.abs(one.x() - middleX) - Math.abs(other.x() - middleX)));

                if (apart <= bestApart) {
                    bestApart = apart;
                    bestLeft = one;
                    bestRight = other;
                }
            }
        }

        if (bestLeft == null) {
            return;
        }

        if (left == null) {
            take(bestLeft, leftName, have, out);
            spare.getOrDefault(leftName, new ArrayList<>()).remove(bestLeft);
        }

        if (right == null) {
            take(bestRight, rightName, have, out);
            spare.getOrDefault(rightName, new ArrayList<>()).remove(bestRight);
        }
    }

    /**
     * A limb is posed as far down as it is whole: a bone there is on one
     * side only, or one whose limb is missing above it, is held with the
     * body instead. Posed alone it would be put where Epic Fight's joint
     * is while what carries it stayed where it was.
     */
    private static void whole(Map<String, Part> have, Settled out) {
        for (String[] chain : new String[][] { ARM, LEG }) {
            for (String level : chain) {
                boolean left = have.containsKey(SIDES[0] + level);
                boolean right = have.containsKey(SIDES[1] + level);

                // A locator on one hand only is all a model with one needs.
                if (left != right && !level.equals(LOCATOR)) {
                    hold(SIDES[left ? 0 : 1] + level, have, out, "has no fellow on the other side");
                }
            }
        }

        for (String side : SIDES) {
            if (have.containsKey(side + "forearm") && !have.containsKey(side + "arm")) {
                hold(side + "forearm", have, out, "has no arm above it");
            }

            if (have.containsKey(side + "hand") && !have.containsKey(side + "forearm") && !have.containsKey(side + "arm")) {
                hold(side + "hand", have, out, "has no arm above it");
            }

            if (have.containsKey(side + "handlocator") && !have.containsKey(side + "hand")
                    && !have.containsKey(side + "forearm") && !have.containsKey(side + "arm")) {
                hold(side + "handlocator", have, out, "has no arm above it");
            }

            if (have.containsKey(side + "foot") && !have.containsKey(side + "lowerleg")) {
                hold(side + "foot", have, out, "has no leg above it");
            }
        }
    }

    private static void hold(String base, Map<String, Part> have, Settled out, String why) {
        Part part = have.remove(base);

        if (part == null) {
            return;
        }

        // Its own name where that is not a part's name, else marked so
        // that nothing takes it for the part.
        String own = part.name();
        out.renames.put(own, named(own.toLowerCase(Locale.ROOT)) ? own + "~" : own);
        out.notes.add(own + " " + why + " and is held with the body, not posed");
    }

    private static boolean named(String base) {
        if (TRUNK.contains(base) || HEADS.contains(base) || GROUPS.contains(base)) {
            return true;
        }

        for (String side : SIDES) {
            if (base.startsWith(side)) {
                String rest = base.substring(side.length());

                for (String[] chain : new String[][] { ARM, LEG }) {
                    for (String level : chain) {
                        if (rest.equals(level)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /** Whether this is a bone of an arm, or of a leg, of either side. */
    private static boolean partOf(String[] chain, String base) {
        return levelIn(chain, base) >= 0;
    }

    private static int levelIn(String[] chain, String base) {
        for (String side : SIDES) {
            if (base.startsWith(side)) {
                for (int i = 0; i < chain.length; i++) {
                    if (base.substring(side.length()).equals(chain[i])) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    /**
     * How far down the model's list the bones known to hang from each
     * bone reach. Empty when any bone is listed before the one it hangs
     * from: the list is then not in the order this leans on.
     */
    private static Map<String, Integer> ends(List<Part> parts, Map<String, Part> byName) {
        Map<String, Integer> ends = new HashMap<>();

        for (Part part : parts) {
            if (part.order() < 0) {
                continue;
            }

            Part at = part.above() == null ? null : byName.get(part.above());

            for (int step = 0; at != null && step < 64; step++) {
                if (at.order() < 0) {
                    break;
                }

                if (at.order() >= part.order()) {
                    return Map.of();
                }

                ends.merge(at.name(), part.order(), Math::max);
                at = at.above() == null ? null : byName.get(at.above());
            }
        }

        return ends;
    }

    /**
     * Whether this bone hangs from one of the body's limbs, or from its
     * head, without being a part of that limb or of the head.
     */
    private static boolean onAnotherLimb(Part part, String base, Map<String, Part> have, Map<String, Integer> ends) {
        if (part.order() < 0 || ends.isEmpty()) {
            return false;
        }

        for (Map.Entry<String, Part> entry : have.entrySet()) {
            String over = entry.getKey();
            boolean head = HEADS.contains(over);
            String[] chain = partOf(ARM, over) ? ARM : partOf(LEG, over) ? LEG : null;

            if (!head && chain == null) {
                continue;
            }

            Part above = entry.getValue();
            Integer end = ends.get(above.name());

            if (end == null || above.order() < 0 || part.order() <= above.order() || part.order() > end) {
                continue;
            }

            boolean itsOwn = head ? HEADS.contains(base)
                    : base.charAt(0) == over.charAt(0) && levelIn(chain, base) > levelIn(chain, over);

            if (!itsOwn) {
                return true;
            }
        }

        return false;
    }

    private static float apart(Part one, Part other) {
        float x = one.x() - other.x();
        float y = one.y() - other.y();
        float z = one.z() - other.z();
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static String base(String name) {
        return YsmSkeletonOverlay.baseName(name).toLowerCase(Locale.ROOT);
    }

    private static float aside(Part part, float middleX, float middleZ) {
        return (float) Math.hypot(part.x() - middleX, part.z() - middleZ);
    }

    private static float median(List<Part> parts, boolean sideways) {
        float[] values = new float[parts.size()];

        for (int i = 0; i < values.length; i++) {
            values[i] = sideways ? parts.get(i).x() : parts.get(i).z();
        }

        java.util.Arrays.sort(values);
        int half = values.length / 2;
        return values.length % 2 == 1 ? values[half] : (values[half - 1] + values[half]) * 0.5F;
    }
}
