package com.argorice.epicysm.client.convert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * A readable model's own animations, as Yes Steve Model would play them:
 * the bedrock animation files that ship beside the geometry, read into
 * keyframe tracks that can be sampled at any time.
 *
 * Only the everyday clips are kept - standing, walking, running,
 * sneaking, swimming, jumping - and only the bones asked for, which are
 * the ones Epic Fight does not drive itself. Everything a clip does to the
 * body is Epic Fight's business in battle; what it does to a tail, a pair
 * of ears or a hairdo is what this class is for.
 */
public final class OwnAnimation {
    /** The clips a standing, walking or swimming character plays, by the names YSM uses. */
    public static final List<String> PLAYED = List.of(
            "idle", "walk", "run", "sneak", "sneaking", "swim", "swim_stand", "jump", "fly", "elytra_fly", "sleep");

    /** One keyframe: a time and three expressions, usually plain numbers. */
    public record Key(float time, Molang.Expr[] value, boolean smooth) {
    }

    /** One of a bone's three channels. */
    public static final class Channel {
        private final Key[] keys;

        Channel(List<Key> keys) {
            keys.sort((a, b) -> Float.compare(a.time(), b.time()));
            this.keys = keys.toArray(new Key[0]);
        }

        /** True when the value ever changes: more than one key, or an expression. */
        public boolean moves() {
            if (this.keys.length > 1) {
                return true;
            }

            for (Key key : this.keys) {
                for (Molang.Expr expr : key.value()) {
                    if (!Molang.isConstant(expr)) {
                        return true;
                    }
                }
            }

            return false;
        }

        /** The channel's three numbers at the given time, written into out. */
        public void sample(float time, Molang.Context context, float[] out) {
            Key[] keys = this.keys;

            if (keys.length == 0) {
                out[0] = 0.0F;
                out[1] = 0.0F;
                out[2] = 0.0F;
                return;
            }

            if (keys.length == 1 || time <= keys[0].time()) {
                evaluate(keys[0], context, out);
                return;
            }

            Key last = keys[keys.length - 1];

            if (time >= last.time()) {
                evaluate(last, context, out);
                return;
            }

            int index = 0;

            while (index + 1 < keys.length && keys[index + 1].time() <= time) {
                index++;
            }

            Key from = keys[index];
            Key to = keys[index + 1];
            float span = to.time() - from.time();
            float t = span > 1.0E-6F ? (time - from.time()) / span : 0.0F;

            float[] a = TEMP_A.get();
            float[] b = TEMP_B.get();
            evaluate(from, context, a);
            evaluate(to, context, b);

            if (from.smooth() || to.smooth()) {
                // Catmull-Rom through the neighbouring keys, the way the
                // model editor shows the curve; ends repeat their key.
                float[] before = TEMP_C.get();
                float[] after = TEMP_D.get();
                evaluate(keys[Math.max(0, index - 1)], context, before);
                evaluate(keys[Math.min(keys.length - 1, index + 2)], context, after);

                for (int i = 0; i < 3; i++) {
                    out[i] = catmullRom(before[i], a[i], b[i], after[i], t);
                }

                return;
            }

            for (int i = 0; i < 3; i++) {
                out[i] = a[i] + (b[i] - a[i]) * t;
            }
        }

        private static void evaluate(Key key, Molang.Context context, float[] out) {
            Molang.Expr[] value = key.value();

            for (int i = 0; i < 3; i++) {
                float v = value[i].eval(context, null);
                out[i] = Float.isFinite(v) ? v : 0.0F;
            }
        }

        private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
            float t2 = t * t;
            float t3 = t2 * t;
            return 0.5F * ((2.0F * p1) + (-p0 + p2) * t + (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3) * t2
                    + (-p0 + 3.0F * p1 - 3.0F * p2 + p3) * t3);
        }
    }

    private static final ThreadLocal<float[]> TEMP_A = ThreadLocal.withInitial(() -> new float[3]);
    private static final ThreadLocal<float[]> TEMP_B = ThreadLocal.withInitial(() -> new float[3]);
    private static final ThreadLocal<float[]> TEMP_C = ThreadLocal.withInitial(() -> new float[3]);
    private static final ThreadLocal<float[]> TEMP_D = ThreadLocal.withInitial(() -> new float[3]);

    /** A bone's channels in one clip; any of them may be missing. */
    public record Track(@Nullable Channel rotation, @Nullable Channel position, @Nullable Channel scale) {
        public boolean moves() {
            return (this.rotation != null && this.rotation.moves())
                    || (this.position != null && this.position.moves())
                    || (this.scale != null && this.scale.moves());
        }
    }

    /** One animation: its length, whether it loops, and a track per bone. */
    public record Clip(String name, float length, boolean loop, Map<String, Track> bones) {
    }

    private final Map<String, Clip> clips;

    private OwnAnimation(Map<String, Clip> clips) {
        this.clips = clips;
    }

    public static OwnAnimation empty() {
        return new OwnAnimation(Map.of());
    }

    @Nullable
    public Clip clip(String name) {
        return this.clips.get(name);
    }

    public boolean isEmpty() {
        return this.clips.isEmpty();
    }

    public Set<String> clipNames() {
        return this.clips.keySet();
    }

    /**
     * Reads the played clips out of the model's animation files, keeping
     * the tracks of the given bones only.
     */
    public static OwnAnimation read(List<JsonObject> files, Set<String> bones) {
        Map<String, Clip> clips = new LinkedHashMap<>();

        for (JsonObject file : files) {
            JsonObject animations = object(file, "animations");

            if (animations == null) {
                continue;
            }

            for (String name : PLAYED) {
                JsonObject definition = object(animations, name);

                if (definition == null || clips.containsKey(name)) {
                    continue;
                }

                Clip clip = readClip(name, definition, bones);

                if (clip != null) {
                    clips.put(name, clip);
                }
            }
        }

        return new OwnAnimation(clips);
    }

    /**
     * Bones with motion of their own in any played clip: more than one key
     * on a channel, or an expression. A bone with a single constant key is
     * merely posed, and the geometry already sits in that pose.
     */
    public static Set<String> movingBones(List<JsonObject> files) {
        Set<String> result = new HashSet<>();

        for (JsonObject file : files) {
            JsonObject animations = object(file, "animations");

            if (animations == null) {
                continue;
            }

            for (String name : PLAYED) {
                JsonObject definition = object(animations, name);
                JsonObject boneObjects = definition == null ? null : object(definition, "bones");

                if (boneObjects == null) {
                    continue;
                }

                for (Map.Entry<String, JsonElement> entry : boneObjects.entrySet()) {
                    if (!entry.getValue().isJsonObject() || result.contains(entry.getKey())) {
                        continue;
                    }

                    Track track = readTrack(entry.getValue().getAsJsonObject());

                    if (track != null && track.moves()) {
                        result.add(entry.getKey());
                    }
                }
            }
        }

        return result;
    }

    @Nullable
    private static Clip readClip(String name, JsonObject definition, Set<String> wanted) {
        JsonObject boneObjects = object(definition, "bones");

        if (boneObjects == null) {
            return null;
        }

        Map<String, Track> tracks = new HashMap<>();
        float longest = 0.0F;

        for (Map.Entry<String, JsonElement> entry : boneObjects.entrySet()) {
            if (!wanted.contains(entry.getKey()) || !entry.getValue().isJsonObject()) {
                continue;
            }

            Track track = readTrack(entry.getValue().getAsJsonObject());

            if (track != null) {
                tracks.put(entry.getKey(), track);
                longest = Math.max(longest, lastKeyTime(track));
            }
        }

        if (tracks.isEmpty()) {
            return null;
        }

        float length = number(definition, "animation_length", 0.0F);

        if (length <= 0.0F) {
            length = longest;
        }

        boolean loop = false;

        if (definition.has("loop")) {
            JsonElement loopValue = definition.get("loop");

            if (loopValue.isJsonPrimitive()) {
                JsonPrimitive primitive = loopValue.getAsJsonPrimitive();
                // true, or "hold_on_last_frame" - the latter is a clip that
                // stops; only true loops.
                loop = primitive.isBoolean() ? primitive.getAsBoolean()
                        : primitive.isString() && primitive.getAsString().equalsIgnoreCase("true");
            }
        }

        // A standing clip with no length of its own still cycles.
        if (length <= 0.0F) {
            length = 1.0F;
        }

        return new Clip(name, length, loop, tracks);
    }

    private static float lastKeyTime(Track track) {
        float last = 0.0F;

        for (Channel channel : new Channel[] { track.rotation(), track.position(), track.scale() }) {
            if (channel != null && channel.keys.length > 0) {
                last = Math.max(last, channel.keys[channel.keys.length - 1].time());
            }
        }

        return last;
    }

    @Nullable
    private static Track readTrack(JsonObject bone) {
        Channel rotation = readChannel(bone.get("rotation"), false);
        Channel position = readChannel(bone.get("position"), false);
        Channel scale = readChannel(bone.get("scale"), true);

        if (rotation == null && position == null && scale == null) {
            return null;
        }

        return new Track(rotation, position, scale);
    }

    /**
     * A channel is a value, or an object of time -> value. A value is a
     * number, an expression, three of either, or {"post": [...]} with an
     * optional lerp_mode.
     */
    @Nullable
    private static Channel readChannel(@Nullable JsonElement element, boolean uniform) {
        if (element == null || element.isJsonNull()) {
            return null;
        }

        List<Key> keys = new ArrayList<>();

        if (element.isJsonObject() && !isKeyframeValue(element.getAsJsonObject())) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                float time;

                try {
                    time = Float.parseFloat(entry.getKey().trim());
                } catch (NumberFormatException e) {
                    continue;
                }

                Key key = readKey(time, entry.getValue(), uniform);

                if (key != null) {
                    keys.add(key);
                }
            }
        } else {
            Key key = readKey(0.0F, element, uniform);

            if (key != null) {
                keys.add(key);
            }
        }

        return keys.isEmpty() ? null : new Channel(keys);
    }

    private static boolean isKeyframeValue(JsonObject object) {
        return object.has("post") || object.has("pre") || object.has("vector");
    }

    @Nullable
    private static Key readKey(float time, JsonElement element, boolean uniform) {
        boolean smooth = false;

        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement mode = object.get("lerp_mode");
            smooth = mode != null && mode.isJsonPrimitive() && mode.getAsString().equalsIgnoreCase("catmullrom");
            element = object.has("post") ? object.get("post") : object.has("vector") ? object.get("vector") : object.get("pre");

            if (element == null) {
                return null;
            }
        }

        Molang.Expr[] value = new Molang.Expr[3];

        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();

            for (int i = 0; i < 3; i++) {
                JsonElement item = i < array.size() ? array.get(i) : null;
                value[i] = item == null ? (i > 0 && array.size() == 1 ? value[0] : Molang.constant(uniform ? 1.0F : 0.0F)) : expression(item);
            }
        } else if (element.isJsonPrimitive()) {
            Molang.Expr single = expression(element);
            value[0] = single;
            value[1] = single;
            value[2] = single;
        } else {
            return null;
        }

        return new Key(time, value, smooth);
    }

    private static Molang.Expr expression(JsonElement element) {
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();

            if (primitive.isNumber()) {
                return Molang.constant(primitive.getAsFloat());
            }

            if (primitive.isBoolean()) {
                return Molang.constant(primitive.getAsBoolean() ? 1.0F : 0.0F);
            }

            Molang.Expr compiled = Molang.compile(primitive.getAsString());
            return compiled != null ? compiled : Molang.constant(0.0F);
        }

        return Molang.constant(0.0F);
    }

    @Nullable
    private static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static float number(JsonObject parent, String key, float fallback) {
        JsonElement element = parent.get(key);

        if (element != null && element.isJsonPrimitive()) {
            try {
                return element.getAsFloat();
            } catch (RuntimeException ignored) {
            }
        }

        return fallback;
    }

    /** For the log: which clips were read and how many bones each moves. */
    public String describe() {
        StringBuilder text = new StringBuilder();

        for (Clip clip : this.clips.values()) {
            text.append(text.length() > 0 ? ", " : "").append(clip.name()).append(" (")
                    .append(clip.bones().size()).append(" bone(s), ")
                    .append(String.format(Locale.ROOT, "%.2f", clip.length())).append("s")
                    .append(clip.loop() ? ", loops)" : ")");
        }

        return text.toString();
    }
}
