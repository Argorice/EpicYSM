package com.argorice.epicysm.client.convert;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Finds bones a YSM model keeps hidden in its default (idle) state. */
public final class AnimationHiddenBones {
    private AnimationHiddenBones() {
    }

    /** Bone names hidden by the always-playing animations of the model. */
    public static Set<String> collect(List<JsonObject> animationFiles, List<JsonObject> controllerFiles) {
        // All animations by name, wherever they are defined.
        Map<String, JsonObject> animations = new HashMap<>();

        for (JsonObject file : animationFiles) {
            if (file.has("animations") && file.get("animations").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : file.getAsJsonObject("animations").entrySet()) {
                    if (entry.getValue().isJsonObject()) {
                        animations.put(entry.getKey(), entry.getValue().getAsJsonObject());
                    }
                }
            }
        }

        // Animations active in the default state: the plain parallel ones
        // plus everything the initial state of each controller plays.
        Set<String> active = new HashSet<>();

        for (String name : animations.keySet()) {
            if (name.startsWith("parallel") || name.startsWith("pre_parallel")) {
                active.add(name);
            }
        }

        for (JsonObject file : controllerFiles) {
            collectInitialStateAnimations(file, active);
        }

        Set<String> hidden = new HashSet<>();

        for (String name : active) {
            JsonObject definition = animations.get(name);

            if (definition == null || !definition.has("bones") || !definition.get("bones").isJsonObject()) {
                continue;
            }

            for (Map.Entry<String, JsonElement> bone : definition.getAsJsonObject("bones").entrySet()) {
                if (bone.getValue().isJsonObject()) {
                    JsonElement scale = bone.getValue().getAsJsonObject().get("scale");

                    if (scale != null && hidesByScale(scale)) {
                        hidden.add(bone.getKey());
                    }
                }
            }
        }

        return hidden;
    }

    /**
     * Adds the animations a controller plays in the DEFAULT state of the
     * world: starting from its initial state, transitions whose conditions
     * already hold with default molang values are followed (a controller
     * often leaves its initial state immediately - e.g. an intro state that
     */
    private static void collectInitialStateAnimations(JsonObject file, Set<String> active) {
        if (!file.has("animation_controllers") || !file.get("animation_controllers").isJsonObject()) {
            return;
        }

        for (Map.Entry<String, JsonElement> controller : file.getAsJsonObject("animation_controllers").entrySet()) {
            if (!controller.getValue().isJsonObject()) {
                continue;
            }

            JsonObject definition = controller.getValue().getAsJsonObject();

            if (!definition.has("states") || !definition.get("states").isJsonObject()) {
                continue;
            }

            JsonObject states = definition.getAsJsonObject("states");
            String stateName = definition.has("initial_state") ? definition.get("initial_state").getAsString() : "default";

            if (!states.has(stateName) || !states.get(stateName).isJsonObject()) {
                stateName = null;

                for (Map.Entry<String, JsonElement> first : states.entrySet()) {
                    if (first.getValue().isJsonObject()) {
                        stateName = first.getKey();
                        break;
                    }
                }
            }

            if (stateName == null) {
                continue;
            }

            JsonObject state = settleState(states, stateName);

            if (state == null || !state.has("animations") || !state.get("animations").isJsonArray()) {
                continue;
            }

            for (JsonElement entry : state.getAsJsonArray("animations")) {
                if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                    active.add(entry.getAsString());
                } else if (entry.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> conditional : entry.getAsJsonObject().entrySet()) {
                        JsonElement condition = conditional.getValue();

                        if (condition.isJsonPrimitive() && condition.getAsJsonPrimitive().isString()) {
                            Float value = MolangDefault.evaluate(condition.getAsString());

                            if (value != null && value != 0.0F) {
                                active.add(conditional.getKey());
                            }
                        } else {
                            active.add(conditional.getKey());
                        }
                    }
                }
            }
        }
    }

    /** Follows default-true transitions until the machine rests (or loops). */
    private static JsonObject settleState(JsonObject states, String stateName) {
        Set<String> visited = new HashSet<>();
        JsonObject state = states.getAsJsonObject(stateName);

        while (state != null && visited.add(stateName)) {
            String next = null;

            if (state.has("transitions") && state.get("transitions").isJsonArray()) {
                outer:
                for (JsonElement transition : state.getAsJsonArray("transitions")) {
                    if (!transition.isJsonObject()) {
                        continue;
                    }

                    for (Map.Entry<String, JsonElement> entry : transition.getAsJsonObject().entrySet()) {
                        JsonElement condition = entry.getValue();

                        if (condition.isJsonPrimitive() && condition.getAsJsonPrimitive().isString()) {
                            Float value = MolangDefault.evaluate(condition.getAsString());

                            if (value != null && value != 0.0F) {
                                next = entry.getKey();
                                break outer;
                            }
                        }
                    }
                }
            }

            if (next == null || !states.has(next) || !states.get(next).isJsonObject()) {
                break;
            }

            stateName = next;
            state = states.getAsJsonObject(stateName);
        }

        return state;
    }

    private static boolean hidesByScale(JsonElement scale) {
        ScaleSummary summary = new ScaleSummary();
        summarize(scale, summary);
        return summary.sawValue && !summary.nonZero && !summary.unknown;
    }

    private static final class ScaleSummary {
        boolean sawValue;
        boolean nonZero;
        boolean unknown;
    }

    private static void summarize(JsonElement element, ScaleSummary summary) {
        if (element.isJsonPrimitive()) {
            summarizePrimitive(element.getAsJsonPrimitive(), summary);
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();

            for (JsonElement item : array) {
                summarize(item, summary);
            }
        } else if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                // Keyframe metadata, not values.
                if ("lerp_mode".equals(entry.getKey()) || "easing".equals(entry.getKey())) {
                    continue;
                }

                summarize(entry.getValue(), summary);
            }
        }
    }

    private static void summarizePrimitive(JsonPrimitive primitive, ScaleSummary summary) {
        if (primitive.isNumber()) {
            summary.sawValue = true;

            if (primitive.getAsFloat() != 0.0F) {
                summary.nonZero = true;
            }

            return;
        }

        if (!primitive.isString()) {
            return;
        }

        Float value = MolangDefault.evaluate(primitive.getAsString());

        if (value == null) {
            summary.unknown = true;
        } else {
            summary.sawValue = true;

            if (value != 0.0F) {
                summary.nonZero = true;
            }
        }
    }
}
