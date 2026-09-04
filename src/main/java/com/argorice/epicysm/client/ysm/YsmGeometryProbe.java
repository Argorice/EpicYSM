package com.argorice.epicysm.client.ysm;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

import com.argorice.epicysm.EpicYsm;

/**
 * Writes down the shape of the model Yes Steve Model currently has in
 * memory, so this mod can learn to draw that model itself.
 */
public final class YsmGeometryProbe {
    private static final String YSM_PACKAGE = "com.elfmcys.";
    private static final int MAX_NODES = 200_000;
    private static final int MAX_DEPTH = 16;
    private static final int HOPS_FROM_BONE = 3;
    private static final int MAX_CLASSES = 80;

    private static final Set<String> BONE_NAMES = Set.of(
            "root", "allbody", "upbody", "downbody", "upperbody", "allhead", "head",
            "leftarm", "rightarm", "leftforearm", "rightforearm", "lefthand", "righthand",
            "leftleg", "rightleg", "leftlowerleg", "rightlowerleg", "leftfoot", "rightfoot");

    private static boolean done;

    private YsmGeometryProbe() {
    }

    /** Runs at most once per game session, and never on the render path twice. */
    public static void runOnce(AbstractClientPlayer player, Object renderer, @Nullable ResourceLocation texture) {
        if (done || renderer == null) {
            return;
        }

        done = true;

        try {
            Path file = write(player, renderer, texture);

            if (file != null) {
                com.argorice.epicysm.client.Diag.info("Wrote a description of the model Yes Steve Model has in memory to {}", file);
            }
        } catch (Throwable t) {
            EpicYsm.LOGGER.warn("Could not describe the model Yes Steve Model has in memory", t);
        }

        try {
            Path file = writeAnimations(renderer);

            if (file != null) {
                com.argorice.epicysm.client.Diag.info("Wrote a description of the animations Yes Steve Model plays to {}", file);
            }
        } catch (Throwable t) {
            EpicYsm.LOGGER.warn("Could not describe the animations Yes Steve Model plays", t);
        }
    }

    /** Names an animation is likely to have, whoever wrote it. */
    private static final String[] ANIMATION_WORDS = {
            "swing", "attack", "idle", "walk", "run", "sneak", "sprint", "jump",
            "fall", "sit", "swim", "die", "hurt", "use", "eat", "bow", "block", "dash"
    };

    /** Writes down what Yes Steve Model's animation registry looks like. */
    @Nullable
    private static Path writeAnimations(Object renderer) throws Exception {
        List<String> report = new ArrayList<>();
        report.add("EpicYSM animation-registry description");
        report.add("");

        Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> queue = new ArrayDeque<>();

        for (Object seed : seeds(null, renderer)) {
            if (seed != null) {
                queue.add(new Object[] { seed, 0 });
            }
        }

        int found = 0;
        int nodes = 0;

        while (!queue.isEmpty() && nodes < MAX_NODES && found < 4) {
            Object[] entry = queue.poll();
            Object value = entry[0];
            int depth = (Integer) entry[1];

            if (value == null || !visited.add(value)) {
                continue;
            }

            nodes++;

            if (value instanceof Map<?, ?> map && looksLikeAnimations(map)) {
                found++;
                describeRegistry(map, report);
            }

            if (depth < 10) {
                for (Object child : childrenOf(value)) {
                    if (child != null && !visited.contains(child)) {
                        queue.add(new Object[] { child, depth + 1 });
                    }
                }
            }
        }

        report.add("");
        report.add("walked " + nodes + " node(s); " + found + " animation registry/registries described");
        return save(report, "ysm-animations.txt");
    }

    private static boolean looksLikeAnimations(Map<?, ?> map) {
        if (map.size() < 4 || map.size() > 20000) {
            return false;
        }

        int hits = 0;
        int seen = 0;

        try {
            for (Object key : map.keySet()) {
                if (!(key instanceof String name)) {
                    return false;
                }

                if (++seen > 64) {
                    break;
                }

                String lower = name.toLowerCase(Locale.ROOT);

                if (lower.indexOf(':') >= 0) {
                    hits++;
                    continue;
                }

                for (String word : ANIMATION_WORDS) {
                    if (lower.contains(word)) {
                        hits++;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            return false;
        }

        return hits >= 3;
    }

    private static void describeRegistry(Map<?, ?> map, List<String> report) {
        report.add("=== REGISTRY " + shortName(map.getClass().getName()) + " with " + map.size() + " entries ===");
        List<String> keys = new ArrayList<>();

        for (Object key : map.keySet()) {
            keys.add(String.valueOf(key));

            if (keys.size() >= 60) {
                break;
            }
        }

        report.add("keys: " + keys);
        report.add("");

        // The ones worth opening up: something that swings, and anything.
        int opened = 0;

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            boolean interesting = key.contains("swing") || key.contains("attack") || key.contains("idle");

            if (!interesting && opened > 0) {
                continue;
            }

            if (entry.getValue() == null) {
                continue;
            }

            report.add("--- entry \"" + entry.getKey() + "\" -> " + shortName(entry.getValue().getClass().getName()));
            describe(entry.getValue(), report, "    ", 3);
            report.add("");

            if (++opened >= 3) {
                break;
            }
        }
    }

    @Nullable
    private static Path write(AbstractClientPlayer player, Object renderer, @Nullable ResourceLocation texture) throws Exception {
        List<String> report = new ArrayList<>();
        report.add("EpicYSM live-geometry description");
        report.add("player=" + player.getName().getString() + " texture=" + texture);
        report.add("");

        Map<Object, Object> parents = new IdentityHashMap<>();
        List<Object> bones = new ArrayList<>();
        List<Object> textureHolders = new ArrayList<>();
        int nodes = walk(player, renderer, texture, parents, bones, textureHolders);

        report.add("walked " + nodes + " node(s); " + bones.size() + " bone-like object(s); "
                + textureHolders.size() + " object(s) holding the live texture");
        report.add("");

        if (bones.isEmpty()) {
            report.add("no bone found - nothing to describe");
            return save(report);
        }

        // Everything within a few steps of a bone: the cubes have to be there.
        Map<String, Object> examples = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> queue = new ArrayDeque<>();

        for (Object bone : bones) {
            queue.add(new Object[] { bone, 0 });
            Object owner = parents.get(bone);

            if (owner != null) {
                queue.add(new Object[] { owner, 1 });
            }
        }

        while (!queue.isEmpty()) {
            Object[] entry = queue.poll();
            Object value = entry[0];
            int hops = (Integer) entry[1];

            if (value == null || !seen.add(value)) {
                continue;
            }

            if (isYsmClass(value.getClass())) {
                String name = value.getClass().getName();
                counts.merge(name, 1, Integer::sum);

                if (examples.size() < MAX_CLASSES) {
                    examples.putIfAbsent(name, value);
                }
            }

            if (hops < HOPS_FROM_BONE) {
                for (Object child : childrenOf(value)) {
                    if (child != null && !seen.contains(child)) {
                        queue.add(new Object[] { child, hops + 1 });
                    }
                }
            }
        }

        report.add("=== CLASSES WITHIN " + HOPS_FROM_BONE + " STEPS OF A BONE ===");

        for (Map.Entry<String, Object> entry : examples.entrySet()) {
            report.add("");
            report.add(shortName(entry.getKey()) + "  x" + counts.get(entry.getKey()));
            describe(entry.getValue(), report, "    ", 2);
        }

        return save(report);
    }

    /** Every field of one object, with numbers summarised rather than dumped. */
    private static void describe(Object value, List<String> report, String indent, int depth) {
        for (Class<?> type = value.getClass(); type != null && isYsmClass(type); type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible()) {
                    continue;
                }

                Object held;

                try {
                    held = field.get(value);
                } catch (Throwable ignored) {
                    continue;
                }

                report.add(indent + field.getType().getSimpleName() + " " + field.getName() + " = " + summarise(held));

                if (depth <= 0) {
                    continue;
                }

                // What is inside a list matters more than its length.
                List<Object> sample = new ArrayList<>();

                if (held instanceof Collection<?> collection) {
                    for (Object element : collection) {
                        if (sample.size() >= 3) {
                            break;
                        }

                        sample.add(element);
                    }
                } else if (held != null && held.getClass().isArray() && !held.getClass().getComponentType().isPrimitive()) {
                    for (int i = 0; i < Math.min(Array.getLength(held), 3); i++) {
                        sample.add(Array.get(held, i));
                    }
                }

                String names = boneNames(held);

                if (!names.isEmpty()) {
                    report.add(indent + "  names: " + names);
                }

                for (Object element : sample) {
                    if (element != null && isYsmClass(element.getClass())) {
                        report.add(indent + "  [" + shortName(element.getClass().getName()) + "]");
                        describe(element, report, indent + "    ", depth - 1);
                    }
                }
            }
        }
    }

    /** The bone names inside a collection, which is what a group is. */
    private static String boneNames(@Nullable Object held) {
        if (!(held instanceof Collection<?> collection) || collection.isEmpty() || collection.size() > 64) {
            return "";
        }

        StringBuilder text = new StringBuilder();

        for (Object element : collection) {
            if (element == null || !isYsmClass(element.getClass())) {
                return "";
            }

            String name = nameOf(element);

            if (name == null) {
                return "";
            }

            text.append(text.length() == 0 ? "" : ", ").append(name);
        }

        return text.toString();
    }

    @Nullable
    private static String nameOf(Object value) {
        for (Class<?> type = value.getClass(); type != null && isYsmClass(type); type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                    continue;
                }

                try {
                    if (field.trySetAccessible() && field.get(value) instanceof String string) {
                        return string;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return null;
    }

    private static String summarise(@Nullable Object value) {
        if (value == null) {
            return "null";
        }

        Class<?> type = value.getClass();

        if (type.isArray()) {
            int length = Array.getLength(value);
            StringBuilder text = new StringBuilder(type.getComponentType().getSimpleName() + "[" + length + "]");

            if (type.getComponentType().isPrimitive() && length > 0) {
                text.append(" {");

                for (int i = 0; i < Math.min(length, 8); i++) {
                    text.append(i > 0 ? ", " : "").append(Array.get(value, i));
                }

                text.append(length > 8 ? ", ...}" : "}");
            }

            return text.toString();
        }

        if (value instanceof Collection<?> collection) {
            return "Collection[" + collection.size() + "] of "
                    + collection.stream().findFirst().map(first -> shortName(first.getClass().getName())).orElse("?");
        }

        if (value instanceof Map<?, ?> map) {
            return "Map[" + map.size() + "]";
        }

        if (value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?> || value instanceof ResourceLocation) {
            return String.valueOf(value);
        }

        return shortName(type.getName());
    }

    /* ------------------------------------------------------------------ */

    private static int walk(AbstractClientPlayer player, Object renderer, @Nullable ResourceLocation texture,
                            Map<Object, Object> parents, List<Object> bones, List<Object> textureHolders) {
        Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Object, Integer> depths = new IdentityHashMap<>();
        Deque<Object> queue = new ArrayDeque<>();

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
                        parents.put(child, value);
                        queue.add(child);
                    }
                }
            }

            if (!isYsmClass(value.getClass())) {
                continue;
            }

            if (texture != null && textureHolders.size() < 8 && holds(value, texture)) {
                textureHolders.add(value);
            }

            if (bones.size() < 48 && isBone(value)) {
                bones.add(value);
            }
        }

        return nodes;
    }

    private static boolean isBone(Object value) {
        boolean named = false;
        boolean vector = false;

        for (Class<?> type = value.getClass(); type != null && isYsmClass(type); type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible()) {
                    continue;
                }

                try {
                    Object held = field.get(value);

                    if (held instanceof String string && BONE_NAMES.contains(string.toLowerCase(Locale.ROOT))) {
                        named = true;
                    } else if (held instanceof org.joml.Vector3f) {
                        vector = true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return named && vector;
    }

    private static boolean holds(Object value, ResourceLocation texture) {
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

    private static Path save(List<String> report) throws Exception {
        return save(report, "ysm-geometry.txt");
    }

    private static Path save(List<String> report, String fileName) throws Exception {
        Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("config/epicysm/" + fileName);
        Files.createDirectories(file.getParent());
        Files.write(file, report, StandardCharsets.UTF_8);
        return file;
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

    private static String shortName(String className) {
        return className.startsWith(YSM_PACKAGE) ? className.substring(YSM_PACKAGE.length()) : className;
    }
}
