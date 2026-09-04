package com.argorice.epicysm.client;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;

import com.argorice.epicysm.EpicYsm;
import com.argorice.epicysm.client.model.YsmModelSource;

/**
 * Best-effort discovery of the model id another mod's (YSM's) player
 * renderer is currently using.
 */
public final class YsmModelProbe {
    private static final String FOREIGN_PACKAGE = "com.elfmcys.";
    private static final int MAX_NODES = 60000;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_LOGGED_STRINGS = 40;

    private YsmModelProbe() {
    }

    /**
     * @param renderer   the foreign renderer instance
     * @param playerId   UUID of the player being rendered
     * @param texture    the texture the foreign renderer currently uses
     */
    public static String probe(Object renderer, UUID playerId, ResourceLocation texture, Map<String, String> candidates) {
        try {
            return runProbe(renderer, playerId, texture, candidates);
        } catch (Throwable e) {
            EpicYsm.LOGGER.warn("Model probe failed", e);
            return "";
        }
    }

    private static final class Node {
        final Object value;
        final int depth;
        final boolean focused;

        Node(Object value, int depth, boolean focused) {
            this.value = value;
            this.depth = depth;
            this.focused = focused;
        }
    }

    private static String runProbe(Object renderer, UUID playerId, ResourceLocation texture, Map<String, String> candidates) {
        Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Class<?>> visitedStatics = new HashSet<>();
        Deque<Node> queue = new ArrayDeque<>();
        List<String> hotMatches = new ArrayList<>();
        List<String> focusedMatches = new ArrayList<>();
        Set<String> globalMatches = new HashSet<>();
        List<String> seenStrings = new ArrayList<>();
        int nodes = 0;

        queue.add(new Node(renderer, 0, false));

        // The model and layers live in vanilla superclass fields, which the
        // foreign-class field walk deliberately skips; seed them explicitly.
        if (renderer instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> livingRenderer) {
            queue.add(new Node(livingRenderer.getModel(), 1, false));

            // The layers field is protected, and Forge runs on SRG names at
            // runtime, so it is found by its type rather than its name.
            try {
                for (Field field : net.minecraft.client.renderer.entity.LivingEntityRenderer.class.getDeclaredFields()) {
                    if (java.util.List.class.isAssignableFrom(field.getType()) && field.trySetAccessible()) {
                        queue.add(new Node(field.get(livingRenderer), 1, false));
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        while (!queue.isEmpty() && nodes < MAX_NODES) {
            Node node = queue.poll();
            Object value = node.value;

            if (value == null || !visited.add(value)) {
                continue;
            }

            nodes++;

            if (value instanceof String string) {
                collectString(string, node.focused, candidates, focusedMatches, globalMatches, seenStrings);
                continue;
            }

            if (node.depth >= MAX_DEPTH) {
                continue;
            }

            if (value instanceof Map<?, ?> map) {
                expandMap(map, node, playerId, queue);
                continue;
            }

            if (value instanceof Collection<?> collection) {
                for (Object element : limit(collection)) {
                    queue.add(new Node(element, node.depth + 1, node.focused));
                }

                continue;
            }

            if (value instanceof Optional<?> optional) {
                optional.ifPresent(inner -> queue.add(new Node(inner, node.depth + 1, node.focused)));
                continue;
            }

            if (value.getClass().isArray()) {
                if (!value.getClass().getComponentType().isPrimitive()) {
                    int length = Math.min(Array.getLength(value), 256);

                    for (int i = 0; i < length; i++) {
                        queue.add(new Node(Array.get(value, i), node.depth + 1, node.focused));
                    }
                }

                continue;
            }

            if (isForeign(value)) {
                boolean hot = expandForeignObject(value, node, texture, queue, visitedStatics);

                if (hot) {
                    // This YSM object references the texture being drawn:
                    // its strings almost certainly identify the model.
                    collectObjectStrings(value, candidates, hotMatches, seenStrings);
                }
            }
        }

        String result = firstDistinct(hotMatches);

        if (result.isEmpty()) {
            result = firstDistinct(focusedMatches);
        }

        if (result.isEmpty() && globalMatches.size() == 1) {
            result = globalMatches.iterator().next();
        }

        if (result.isEmpty()) {
            com.argorice.epicysm.client.Diag.info("Model probe found no match for texture {} ({} nodes); strings seen: {}", texture, nodes, seenStrings);
        }

        return result;
    }

    private static void expandMap(Map<?, ?> map, Node node, UUID playerId, Deque<Node> queue) {
        // A UUID-keyed map is almost certainly "player -> model data":
        // descend only into this player's entry, marked as focused.
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof UUID uuid) {
                if (playerId.equals(uuid)) {
                    queue.add(new Node(entry.getValue(), node.depth + 1, true));
                }
            } else {
                queue.add(new Node(entry.getKey(), node.depth + 1, node.focused));
                queue.add(new Node(entry.getValue(), node.depth + 1, node.focused));
            }
        }
    }

    /** Queues the object's fields; returns true when a field holds the target texture. */
    private static boolean expandForeignObject(Object value, Node node, ResourceLocation texture, Deque<Node> queue, Set<Class<?>> visitedStatics) {
        boolean referencesTexture = false;

        for (Class<?> type = value.getClass(); type != null && isForeignClass(type); type = type.getSuperclass()) {
            boolean scanStatics = visitedStatics.add(type);

            for (Field field : type.getDeclaredFields()) {
                if (field.getType().isPrimitive()) {
                    continue;
                }

                boolean isStatic = Modifier.isStatic(field.getModifiers());

                if (isStatic && !scanStatics) {
                    continue;
                }

                if (!field.trySetAccessible()) {
                    continue;
                }

                try {
                    Object fieldValue = field.get(isStatic ? null : value);

                    if (fieldValue instanceof ResourceLocation location && location.equals(texture)) {
                        referencesTexture = true;
                    } else if (fieldValue instanceof String string
                            && (string.equals(texture.toString()) || string.equals(texture.getPath()))) {
                        referencesTexture = true;
                    }

                    queue.add(new Node(fieldValue, node.depth + 1, node.focused));
                } catch (Throwable ignored) {
                }
            }
        }

        return referencesTexture;
    }

    /** Collects candidate matches from the direct string fields of one object. */
    private static void collectObjectStrings(Object value, Map<String, String> candidates, List<String> matches, List<String> seenStrings) {
        for (Class<?> type = value.getClass(); type != null && isForeignClass(type); type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != String.class || Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible()) {
                    continue;
                }

                try {
                    if (field.get(value) instanceof String string) {
                        collectString(string, true, candidates, matches, new HashSet<>(), seenStrings);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void collectString(String string, boolean focused, Map<String, String> candidates,
                                      List<String> focusedMatches, Set<String> globalMatches, List<String> seenStrings) {
        if (string.isEmpty() || string.length() > 128) {
            return;
        }

        String sanitized = YsmModelSource.sanitize(string.trim());
        String match = candidates.get(sanitized);

        if (match != null) {
            if (focused) {
                focusedMatches.add(match);
            } else {
                globalMatches.add(match);
            }
        } else if (seenStrings.size() < MAX_LOGGED_STRINGS && string.length() <= 48 && !string.isBlank()) {
            seenStrings.add(string);
        }
    }

    private static String firstDistinct(List<String> matches) {
        return matches.isEmpty() ? "" : matches.get(0);
    }

    private static boolean isForeign(Object value) {
        return isForeignClass(value.getClass());
    }

    private static boolean isForeignClass(Class<?> type) {
        return type.getName().startsWith(FOREIGN_PACKAGE);
    }

    private static Iterable<?> limit(Collection<?> collection) {
        if (collection.size() <= 512) {
            return collection;
        }

        List<Object> limited = new ArrayList<>(512);
        int count = 0;

        for (Object element : collection) {
            limited.add(element);

            if (++count >= 512) {
                break;
            }
        }

        return limited;
    }
}
