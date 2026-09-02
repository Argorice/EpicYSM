package com.argorice.epicysm.client.ysm;

import java.io.IOException;
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
import java.util.HashMap;
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

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

import com.argorice.epicysm.EpicYsm;

/** Maps out Yes Steve Model's live object graph and writes a report. */
public final class YsmInspector {
    private static final String YSM_PACKAGE = "com.elfmcys.";
    private static final int MAX_NODES = 400_000;
    private static final int MAX_INSTANCES_PER_CLASS = 40;
    private static final int MAX_DEPTH = 20;
    private static final int BRIDGE_DEPTH = 6;
    private static final int MAX_COLLECTION_SAMPLE = 128;
    private static final int MAX_REPORT_LINES = 9000;
    private static final long TIME_BUDGET_MS = 25_000L;

    /** Names of the standard YSM skeleton; the anchor of the whole search. */
    private static final Set<String> SKELETON_NAMES = Set.of(
            "root", "mroot", "allbody", "mallbody", "upbody", "mupbody", "downbody",
            "upperbody", "mupperbody", "allhead", "mhead", "head", "body",
            "leftarm", "rightarm", "leftforearm", "rightforearm", "lefthand", "righthand",
            "leftleg", "rightleg", "leftlowerleg", "rightlowerleg", "leftfoot", "rightfoot");

    private YsmInspector() {
    }

    public static Path inspect(AbstractClientPlayer player, Object renderer, ResourceLocation texture) {
        try {
            return new Run(player, renderer, texture).execute();
        } catch (Throwable t) {
            EpicYsm.LOGGER.error("YSM inspection failed", t);
            return null;
        }
    }

    /* ------------------------------------------------------------------ */

    private record Node(Object value, String path, int depth, boolean focused) {
    }

    private static final class Run {
        private final AbstractClientPlayer player;
        private final Object renderer;
        private final ResourceLocation texture;
        private final long deadline = System.currentTimeMillis() + TIME_BUDGET_MS;

        private final Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Class<?>> staticsDone = new HashSet<>();
        private final Map<Class<?>, Integer> perClass = new HashMap<>();
        private final Deque<Node> focusedQueue = new ArrayDeque<>();
        private final Deque<Node> queue = new ArrayDeque<>();
        private final List<String> report = new ArrayList<>();
        private final Map<String, Integer> classCounts = new HashMap<>();

        /** class -> one live example, for the verbatim dump at the end. */
        private final Map<String, Object> examples = new LinkedHashMap<>();

        /** paths where an object carrying a skeleton bone name was found. */
        private final List<String> skeletonPaths = new ArrayList<>();
        private final List<String> playerStatePaths = new ArrayList<>();
        private int nodes;

        Run(AbstractClientPlayer player, Object renderer, ResourceLocation texture) {
            this.player = player;
            this.renderer = renderer;
            this.texture = texture;
        }

        Path execute() throws IOException {
            this.line("EpicYSM inspection report v3");
            this.line("player=" + this.player.getName().getString() + " uuid=" + this.player.getUUID());
            this.line("renderer=" + describeClass(this.renderer));
            this.line("texture=" + this.texture);
            this.line("");

            // The renderer verbatim: whatever holds the live model is here
            // or one hop away, and this dump does not filter anything out.
            this.line("=== RENDERER TREE (all fields, depth 5) ===");
            this.dumpTree(this.renderer, "renderer", 0, 5, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
            this.line("");

            this.line("=== PLAYER ENTITY: YSM references (depth 4) ===");
            this.dumpYsmReferences(this.player, "player", 0, 4, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
            this.line("");

            this.seed();
            this.walk();

            this.line("=== WHERE SKELETON BONE NAMES WERE FOUND ===");

            for (String path : this.skeletonPaths) {
                this.line("  " + path);
            }

            this.line("");
            this.line("=== PER-PLAYER STATE PATHS ===");

            if (this.playerStatePaths.isEmpty()) {
                this.line("  (none found - the live model is not in a UUID-keyed map)");
            }

            for (String path : this.playerStatePaths) {
                this.line("  " + path);
            }

            this.line("");
            this.line("=== EXAMPLE OF EVERY COMMON CLASS (all fields) ===");
            this.classCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(14)
                    .forEach(entry -> {
                        Object example = this.examples.get(entry.getKey());
                        this.line("");
                        this.line("--- " + entry.getKey() + "  (" + entry.getValue() + " instances)");

                        if (example != null) {
                            this.dumpFields(example, "      ");
                        }
                    });

            this.line("");
            this.line("nodes visited: " + this.nodes);

            Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("config/epicysm/ysm-report.txt");
            Files.createDirectories(file.getParent());
            Files.write(file, this.report, StandardCharsets.UTF_8);

            com.argorice.epicysm.client.Diag.info("YSM inspection v3: {} nodes, report written to {}", this.nodes, file);
            return file;
        }

        /* ---------------- verbatim dumps ---------------- */

        private void dumpTree(Object value, String path, int depth, int maxDepth, Set<Object> seen) {
            if (value == null || depth > maxDepth || !seen.add(value) || this.report.size() >= MAX_REPORT_LINES) {
                return;
            }

            String indent = "  ".repeat(depth);
            this.line(indent + path + " : " + describeValue(value));

            if (depth == maxDepth) {
                return;
            }

            if (value instanceof Map<?, ?> map) {
                int shown = 0;

                try {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (shown++ >= 16) {
                            break;
                        }

                        this.dumpTree(entry.getValue(), path + "[" + describeKey(entry.getKey()) + "]", depth + 1, maxDepth, seen);
                    }
                } catch (Throwable t) {
                    this.line("  ".repeat(depth) + "  (map refused iteration: " + t.getClass().getSimpleName() + ")");
                }

                return;
            }

            if (value instanceof Collection<?> collection) {
                int index = 0;

                for (Object element : collection) {
                    if (index >= 16) {
                        break;
                    }

                    this.dumpTree(element, path + "[" + index++ + "]", depth + 1, maxDepth, seen);
                }

                return;
            }

            if (value.getClass().isArray()) {
                if (!value.getClass().getComponentType().isPrimitive()) {
                    int length = Math.min(Array.getLength(value), 16);

                    for (int i = 0; i < length; i++) {
                        this.dumpTree(Array.get(value, i), path + "[" + i + "]", depth + 1, maxDepth, seen);
                    }
                }

                return;
            }

            if (isPlain(value)) {
                return;
            }

            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : safeFields(type)) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }

                    try {
                        if (!field.trySetAccessible()) {
                            continue;
                        }

                        Object fieldValue = field.get(value);

                        if (fieldValue != null && !field.getType().isPrimitive()) {
                            this.dumpTree(fieldValue, path + "." + field.getName(), depth + 1, maxDepth, seen);
                        } else if (fieldValue != null) {
                            this.line("  ".repeat(depth + 1) + path + "." + field.getName() + " = " + fieldValue);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        /** Walks any object, but only prints the YSM objects it reaches. */
        private void dumpYsmReferences(Object value, String path, int depth, int maxDepth, Set<Object> seen) {
            if (value == null || depth > maxDepth || !seen.add(value) || this.report.size() >= MAX_REPORT_LINES) {
                return;
            }

            if (isYsmClass(value.getClass())) {
                this.line("  found: " + path + " : " + describeClass(value));
                this.dumpFields(value, "      ");
                return;
            }

            if (isPlain(value)) {
                return;
            }

            if (value instanceof Map<?, ?> map) {
                int shown = 0;

                try {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (shown++ >= 48) {
                            break;
                        }

                        this.dumpYsmReferences(entry.getValue(), path + "[" + describeKey(entry.getKey()) + "]", depth + 1, maxDepth, seen);
                    }
                } catch (Throwable ignored) {
                }

                return;
            }

            if (value instanceof Collection<?> collection) {
                int index = 0;

                for (Object element : collection) {
                    if (index >= 48) {
                        break;
                    }

                    this.dumpYsmReferences(element, path + "[" + index++ + "]", depth + 1, maxDepth, seen);
                }

                return;
            }

            if (value.getClass().isArray()) {
                if (!value.getClass().getComponentType().isPrimitive()) {
                    int length = Math.min(Array.getLength(value), 48);

                    for (int i = 0; i < length; i++) {
                        this.dumpYsmReferences(Array.get(value, i), path + "[" + i + "]", depth + 1, maxDepth, seen);
                    }
                }

                return;
            }

            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : safeFields(type)) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                    }

                    try {
                        if (field.trySetAccessible()) {
                            this.dumpYsmReferences(field.get(value), path + "." + field.getName(), depth + 1, maxDepth, seen);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private void dumpFields(Object value, String indent) {
            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : safeFields(type)) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }

                    try {
                        if (!field.trySetAccessible()) {
                            continue;
                        }

                        this.line(indent + field.getType().getSimpleName() + " " + field.getName()
                                + " = " + describeValue(field.get(value)));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        /* ---------------- broad search ---------------- */

        private void seed() {
            this.push(this.renderer, "renderer", 0, true);
            this.push(this.player, "player", 0, true);

            try {
                this.push(Minecraft.getInstance().getTextureManager().getTexture(this.texture),
                        "texture[" + this.texture + "]", 0, true);
            } catch (Throwable ignored) {
            }

            Path jar = ysmJarPath();

            if (jar == null) {
                return;
            }

            ClassLoader loader = this.renderer.getClass().getClassLoader();

            try (ZipFile zipFile = new ZipFile(jar.toFile())) {
                var entries = zipFile.entries();

                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();

                    if (!name.endsWith(".class")) {
                        continue;
                    }

                    String className = name.substring(0, name.length() - 6).replace('/', '.');

                    if (className.startsWith(YSM_PACKAGE)) {
                        try {
                            this.pushStatics(Class.forName(className, false, loader), "static:" + shortName(className));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }

        private void pushStatics(Class<?> type, String path) {
            if (!this.staticsDone.add(type)) {
                return;
            }

            for (Field field : safeFields(type)) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }

                try {
                    if (field.trySetAccessible()) {
                        this.push(field.get(null), path + "." + field.getName(), 2, false);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        private void walk() {
            while (this.nodes < MAX_NODES) {
                Node node = !this.focusedQueue.isEmpty() ? this.focusedQueue.poll() : this.queue.poll();

                if (node == null || ((this.nodes & 0x3FF) == 0 && System.currentTimeMillis() > this.deadline)) {
                    return;
                }

                Object value = node.value();

                if (value == null || !this.visited.add(value)) {
                    continue;
                }

                this.nodes++;

                if (node.depth() >= MAX_DEPTH) {
                    continue;
                }

                if (value instanceof Map<?, ?> map) {
                    this.examineMap(map, node);
                } else if (value instanceof Collection<?> collection) {
                    int index = 0;

                    for (Object element : collection) {
                        if (index >= MAX_COLLECTION_SAMPLE) {
                            break;
                        }

                        this.push(element, node.path() + "[" + index++ + "]", node.depth() + 1, node.focused());
                    }
                } else if (value instanceof Optional<?> optional) {
                    optional.ifPresent(inner -> this.push(inner, node.path() + ".get()", node.depth() + 1, node.focused()));
                } else if (value.getClass().isArray()) {
                    if (!value.getClass().getComponentType().isPrimitive()) {
                        int length = Math.min(Array.getLength(value), MAX_COLLECTION_SAMPLE);

                        for (int i = 0; i < length; i++) {
                            this.push(Array.get(value, i), node.path() + "[" + i + "]", node.depth() + 1, node.focused());
                        }
                    }
                } else if (isYsmClass(value.getClass())) {
                    this.examineYsmObject(value, node);
                } else if (node.depth() < BRIDGE_DEPTH && !isPlain(value)) {
                    // Bridge: a vanilla object may be the only route to the
                    // live YSM state (entity attachments, renderer fields).
                    this.expandBridge(value, node);
                }
            }
        }

        private void expandBridge(Object value, Node node) {
            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : safeFields(type)) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                    }

                    try {
                        if (field.trySetAccessible()) {
                            this.push(field.get(value), node.path() + "." + field.getName(), node.depth() + 1, node.focused());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private void examineMap(Map<?, ?> map, Node node) {
            int taken = 0;

            // Some modded maps (Connector's redirecting ones) throw on
            // entrySet(); a walker must survive that, not die on it.
            Set<? extends Map.Entry<?, ?>> entries;

            try {
                entries = map.entrySet();
            } catch (Throwable t) {
                return;
            }

            for (Map.Entry<?, ?> entry : entries) {
                Object key;

                try {
                    key = entry.getKey();
                } catch (Throwable t) {
                    continue;
                }

                if (key instanceof UUID uuid && this.player.getUUID().equals(uuid)) {
                    this.push(entry.getValue(), node.path() + "[THIS_PLAYER]", node.depth() + 1, true);

                    if (this.playerStatePaths.size() < 20) {
                        this.playerStatePaths.add(node.path() + "[THIS_PLAYER] -> " + describeValue(entry.getValue()));
                    }

                    continue;
                }

                if (taken++ < MAX_COLLECTION_SAMPLE) {
                    this.push(entry.getValue(), node.path() + "[" + describeKey(key) + "]", node.depth() + 1, node.focused());
                }
            }
        }

        private void examineYsmObject(Object value, Node node) {
            Class<?> type = value.getClass();
            String className = shortName(type.getName());
            this.classCounts.merge(className, 1, Integer::sum);
            this.examples.putIfAbsent(className, value);

            int seen = this.perClass.merge(type, 1, Integer::sum);

            if (seen > MAX_INSTANCES_PER_CLASS && !node.focused()) {
                return;
            }

            for (Class<?> current = type; current != null && isYsmClass(current); current = current.getSuperclass()) {
                this.pushStatics(current, "static:" + shortName(current.getName()));

                for (Field field : safeFields(current)) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                    }

                    Object fieldValue;

                    try {
                        if (!field.trySetAccessible()) {
                            continue;
                        }

                        fieldValue = field.get(value);
                    } catch (Throwable t) {
                        continue;
                    }

                    if (fieldValue instanceof String string && isSkeletonName(string) && this.skeletonPaths.size() < 40) {
                        this.skeletonPaths.add(node.path() + " (" + className + ", " + field.getName() + "=\"" + string + "\")");
                    }

                    this.push(fieldValue, node.path() + "." + field.getName(), node.depth() + 1, node.focused());
                }
            }
        }

        private void push(Object value, String path, int depth, boolean focused) {
            if (value == null) {
                return;
            }

            Node node = new Node(value, path, depth, focused);

            if (focused) {
                this.focusedQueue.add(node);
            } else {
                this.queue.add(node);
            }
        }

        private void line(String text) {
            if (this.report.size() < MAX_REPORT_LINES) {
                this.report.add(text);
            }
        }
    }

    /* ------------------------------------------------------------------ */

    private static boolean isSkeletonName(String name) {
        return SKELETON_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Values that are never worth walking into. */
    private static boolean isPlain(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?> || value instanceof Class<?>
                || value instanceof UUID || value instanceof ResourceLocation;
    }

    private static String describeValue(Object value) {
        if (value == null) {
            return "null";
        }

        if (isPlain(value)) {
            return value.getClass().getSimpleName() + "(" + trim(String.valueOf(value)) + ")";
        }

        if (value instanceof Map<?, ?> map) {
            return "Map(" + map.size() + ")";
        }

        if (value instanceof Collection<?> collection) {
            return collection.getClass().getSimpleName() + "(" + collection.size() + ")";
        }

        if (value.getClass().isArray()) {
            return value.getClass().getComponentType().getSimpleName() + "[" + Array.getLength(value) + "]";
        }

        String name = value.getClass().getName();

        if (name.startsWith("org.joml.") || name.startsWith("com.mojang.math.")) {
            return value.getClass().getSimpleName() + "(" + trim(String.valueOf(value)) + ")";
        }

        return describeClass(value);
    }

    private static Path ysmJarPath() {
        try {
            var modFile = net.neoforged.fml.ModList.get().getModFileById("yes_steve_model");

            if (modFile != null) {
                return modFile.getFile().getFilePath();
            }
        } catch (Throwable ignored) {
        }

        return null;
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

    private static String describeClass(Object value) {
        return value == null ? "null" : shortName(value.getClass().getName());
    }

    private static String shortName(String className) {
        return className.startsWith(YSM_PACKAGE) ? className.substring(YSM_PACKAGE.length()) : className;
    }

    private static String describeKey(Object key) {
        return key == null ? "null" : trim(String.valueOf(key));
    }

    private static String trim(String text) {
        String single = text.replace('\n', ' ').replace('\r', ' ');
        return single.length() <= 60 ? single : single.substring(0, 60) + "...";
    }
}
