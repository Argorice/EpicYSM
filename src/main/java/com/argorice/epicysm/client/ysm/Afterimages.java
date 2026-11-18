package com.argorice.epicysm.client.ysm;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

import yesman.epicfight.api.client.model.Mesh;

import com.argorice.epicysm.EpicYsm;
import com.argorice.epicysm.client.EpicYsmConfig;
import com.argorice.epicysm.client.ModelManager;

/**
 * Epic Fight's afterimages of a player - the copies of the body a dodge, a
 * step or adrenaline leaves behind - drawn with the model on screen rather
 * than with Epic Fight's own body. Epic Fight makes an afterimage out of a
 * snapshot of the body: its mesh, posed as it stands. A Yes Steve Model
 * model has no mesh of Epic Fight's - Yes Steve Model draws it, or this
 * mod's converted copy does - and the snapshot was of Epic Fight's plain
 * body in the player's skin, a Steve where the model had been a moment
 * before.
 *
 * So the snapshot is of the picture instead. When Epic Fight asks for one,
 * the next draw of that body is kept as well as drawn: every vertex of it,
 * where it stands against the body, with the texture it was drawn with.
 * The afterimage then draws those, in Epic Fight's own afterimage colours,
 * where Epic Fight puts it. A body that is not drawn - the player's own in
 * first person - leaves nothing to keep, and its afterimage is left empty
 * rather than shown as a Steve.
 */
public final class Afterimages {
    /** How long an afterimage waits for the body to be drawn, in nanoseconds: a few frames. */
    private static final long WAITS_FOR = 250_000_000L;

    /** At the most this many vertices are kept of one draw; past it the rest of the body goes unkept. */
    private static final int MOST_VERTICES = 300_000;

    /**
     * How many vertices the afterimages of the model may draw between them
     * in one frame, both of Epic Fight's passes counted. An afterimage of
     * Epic Fight's own body is some 1400 vertices a pass; one of a model
     * is ten to forty thousand, and Epic Fight Nightfall's trails leave an
     * afterimage every frame, thirty and more at once. An afterimage asked
     * for while those on screen already draw this many is left empty.
     */
    private static final int FRAME_BUDGET = 160_000;

    /** An afterimage not drawn for this long, in nanoseconds, is taken to be gone. */
    private static final long GONE_AFTER = 80_000_000L;

    private static final Map<UUID, Waiting> WAITING = new HashMap<>();
    private static final Map<RenderType, Optional<ResourceLocation>> TEXTURES = new WeakHashMap<>();
    private static final List<Ticket> SHOWN = new ArrayList<>();
    private static final Map<UUID, Integer> LAST_COST = new HashMap<>();
    private static boolean saidFirst;
    private static boolean saidTexture;
    private static boolean saidFailure;
    private static boolean saidAsked;
    private static boolean saidUndrawn;
    private static boolean saidBudget;

    private Afterimages() {
    }

    /**
     * Whether the afterimage of this player is to be made out of the model on
     * screen: when Yes Steve Model, or this mod's converted copy of the
     * model, is what draws the player.
     */
    static boolean drawsTheModel(AbstractClientPlayer player) {
        boolean wanted = EpicYsmConfig.modelAfterimages();
        ModelManager models = ModelManager.get();
        boolean converted = wanted && models.modelFor(player) != null;
        boolean overlay = wanted && !converted && models.drawnByYsm(player) && !models.epicFightDraws(player)
                && EpicYsmConfig.skeletonOverlay() && !YsmRenderBridge.disabled();

        // Said once, whichever way it goes: a log with no afterimage of the
        // model in it then tells whether none was asked for at all.
        if (!saidAsked) {
            saidAsked = true;
            EpicYsm.LOGGER.info("Afterimage: Epic Fight asked for a snapshot of {} for an afterimage; {}", player.getName().getString(),
                    !wanted ? "afterimages of the model are switched off in the settings, and Epic Fight makes its own"
                            : converted ? "it is drawn with the converted model, and the afterimage is made out of the next draw of that"
                            : overlay ? "Yes Steve Model draws the model, and the afterimage is made out of the next draw of that"
                            : "Epic Fight draws this player, and makes the afterimage itself");
        }

        return converted || overlay;
    }

    /**
     * A place for one more afterimage of this body among those on screen,
     * or null when they already draw as much as a frame can spare. Decided
     * once, when Epic Fight asks: an afterimage left out stays empty all
     * its life, and is not recorded either, rather than coming and going.
     */
    @Nullable
    static Ticket admit(UUID body) {
        long now = System.nanoTime();
        int load = 0;

        for (Iterator<Ticket> it = SHOWN.iterator(); it.hasNext();) {
            Ticket ticket = it.next();

            if (ticket.gone(now)) {
                it.remove();
            } else {
                load += ticket.cost;
            }
        }

        int cost = LAST_COST.getOrDefault(body, 0);

        if (!SHOWN.isEmpty() && load + cost > FRAME_BUDGET) {
            if (!saidBudget) {
                saidBudget = true;
                EpicYsm.LOGGER.info("Afterimage: {} afterimages of the model on screen already draw {} vertices a frame; the ones asked"
                        + " for past {} are left empty until some of those are gone (a trail of Epic Fight Nightfall's leaves one"
                        + " every frame)", SHOWN.size(), load, FRAME_BUDGET);
            }

            return null;
        }

        Ticket ticket = new Ticket(body, now, cost);
        SHOWN.add(ticket);
        return ticket;
    }

    /** An afterimage of this body waits for its next draw, and is handed what was kept of it. */
    static void await(UUID body, java.util.function.Consumer<Picture> whenDrawn) {
        long now = System.nanoTime();
        forgetStale(now);
        WAITING.computeIfAbsent(body, id -> new Waiting(now)).ghosts.add(whenDrawn);
    }

    /** Said once, when an afterimage could not be made out of the model. */
    static void sayFailure(Throwable t) {
        if (!saidFailure) {
            saidFailure = true;
            EpicYsm.LOGGER.warn("Afterimage: could not make one out of the model on screen; Epic Fight makes its own", t);
        }
    }

    /**
     * The buffers to draw this body into: the ones given, and a recording of
     * them besides while an afterimage of the body is waiting for its
     * picture.
     *
     * @param pose    where the body stands, as the draw begins
     * @param normal  the same for the normals
     * @param texture the model's own texture, for parts drawn with one that cannot be told
     */
    public static MultiBufferSource recordInto(LivingEntity body, MultiBufferSource source, Matrix4f pose, Matrix3f normal,
                                               @Nullable ResourceLocation texture) {
        if (WAITING.isEmpty()) {
            return source;
        }

        Waiting waiting = WAITING.get(body.getUUID());

        if (waiting == null || waiting.recording != null) {
            return source;
        }

        if (System.nanoTime() - waiting.since > WAITS_FOR) {
            WAITING.remove(body.getUUID());
            return source;
        }

        // The body in the world, not the one a screen shows - the inventory
        // draws it many times larger, turned to face whoever looks.
        float size = (float) Math.cbrt(Math.abs(pose.determinant()));

        if (!(size > 0.5F && size < 2.0F)) {
            return source;
        }

        try {
            Picture.Builder builder = new Picture.Builder(pose, normal, texture);
            waiting.builder = builder;
            waiting.recording = new AfterimageRecording(source, builder);
            return waiting.recording;
        } catch (Throwable t) {
            WAITING.remove(body.getUUID());
            return source;
        }
    }

    /** The body is drawn: what was kept of it goes to every afterimage that was waiting. */
    public static void finish(LivingEntity body) {
        Waiting waiting = WAITING.get(body.getUUID());

        if (waiting == null || waiting.builder == null) {
            return;
        }

        WAITING.remove(body.getUUID());
        Picture picture = waiting.builder.build();

        for (java.util.function.Consumer<Picture> ghost : waiting.ghosts) {
            ghost.accept(picture);
        }

        if (!saidFirst) {
            saidFirst = true;
            EpicYsm.LOGGER.info("Afterimage: Epic Fight's afterimage of {} is drawn from the model on screen: {} vertices"
                    + " in {} part(s) ({} more left out as flat or clear), {} part(s) with a texture that could not be told and"
                    + " drawn with the model's own", YsmSkeletonOverlay.nameOf(body), picture.vertices(), picture.parts(),
                    picture.leftOut(), picture.guessed());
        }
    }

    /** The draw failed half-way: nothing of it is kept, and the afterimages stay empty. */
    public static void discard(LivingEntity body) {
        Waiting waiting = WAITING.get(body.getUUID());

        if (waiting != null && waiting.builder != null) {
            WAITING.remove(body.getUUID());
        }
    }

    private static void forgetStale(long now) {
        for (Iterator<Waiting> it = WAITING.values().iterator(); it.hasNext();) {
            Waiting waiting = it.next();

            // One whose draw never finished - it failed half-way - goes too,
            // a little later.
            if (now - waiting.since > (waiting.builder == null ? WAITS_FOR : 4 * WAITS_FOR)) {
                it.remove();

                if (waiting.builder == null && !saidUndrawn) {
                    saidUndrawn = true;
                    EpicYsm.LOGGER.info("Afterimage: the body was not drawn within a quarter of a second of an afterimage being made"
                            + " - seen in first person, or out of sight - and that afterimage is left empty");
                }
            }
        }
    }

    /**
     * The texture a render type draws with. Nothing says so in the open; it
     * is read out of the type's state, by the types of its fields rather
     * than their names, which differ from one game version to the next.
     */
    @Nullable
    static ResourceLocation textureOf(RenderType type) {
        Optional<ResourceLocation> known = TEXTURES.get(type);

        if (known != null) {
            return known.orElse(null);
        }

        ResourceLocation found = null;

        try {
            Object state = fieldOfType(type, "CompositeState");
            Object shard = state == null ? null : fieldOfType(state, "EmptyTextureStateShard");

            for (Class<?> at = shard == null ? null : shard.getClass(); at != null && found == null; at = at.getSuperclass()) {
                for (Field field : at.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && field.getType() == Optional.class) {
                        field.setAccessible(true);

                        if (((Optional<?>) field.get(shard)).orElse(null) instanceof ResourceLocation texture) {
                            found = texture;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            if (!saidTexture) {
                saidTexture = true;
                EpicYsm.LOGGER.info("Afterimage: the texture of a render type could not be read ({}); such parts are drawn with"
                        + " the model's own texture", t.toString());
            }
        }

        TEXTURES.put(type, Optional.ofNullable(found));
        return found;
    }

    @Nullable
    private static Object fieldOfType(Object owner, String typeName) throws IllegalAccessException {
        for (Class<?> at = owner.getClass(); at != null; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && field.getType().getSimpleName().equals(typeName)) {
                    field.setAccessible(true);
                    return field.get(owner);
                }
            }
        }

        return null;
    }

    /** One afterimage of the model on screen, for the budget. */
    static final class Ticket {
        private final UUID body;
        private final long since;
        private long drawn;
        int cost;

        private Ticket(UUID body, long since, int cost) {
            this.body = body;
            this.since = since;
            this.cost = cost;
        }

        /** Its picture came: what it costs a frame is known now, and is the guess for the next one of this body. */
        void priced(Picture picture) {
            this.cost = 2 * picture.drawn();
            LAST_COST.put(this.body, this.cost);
        }

        void drawn() {
            this.drawn = System.nanoTime();
        }

        boolean gone(long now) {
            return this.drawn != 0L ? now - this.drawn > GONE_AFTER : now - this.since > WAITS_FOR + GONE_AFTER;
        }
    }

    /** Afterimages of one body, waiting for it to be drawn. */
    private static final class Waiting {
        final long since;
        final List<java.util.function.Consumer<Picture>> ghosts = new ArrayList<>();
        @Nullable
        Picture.Builder builder;
        @Nullable
        AfterimageRecording recording;

        Waiting(long since) {
            this.since = since;
        }
    }

    /** One draw of a body, kept: its vertices where they stand against the body, by render type. */
    static final class Picture {
        private final List<Part> parts;
        private final int vertices;
        private final int guessed;
        private final int leftOut;
        private final int drawn;

        private Picture(List<Part> parts, int vertices, int guessed, int leftOut) {
            this.parts = parts;
            this.vertices = vertices;
            this.guessed = guessed;
            this.leftOut = leftOut;
            int drawn = 0;

            for (Part part : parts) {
                // Epic Fight draws its afterimages in triangles: a square is six corners.
                drawn += part.corners == 4 ? part.count / 2 * 3 : part.count;
            }

            this.drawn = drawn;
        }

        /** Vertices one pass of the afterimage draws. */
        int drawn() {
            return this.drawn;
        }

        int leftOut() {
            return this.leftOut;
        }

        int vertices() {
            return this.vertices;
        }

        int parts() {
            return this.parts.size();
        }

        int guessed() {
            return this.guessed;
        }

        void draw(PoseStack poseStack, MultiBufferSource buffer, Function<ResourceLocation, RenderType> renderTypes,
                  Mesh.DrawingFunction drawing, int light, float red, float green, float blue, float alpha, Matrix4f undo) {
            Matrix4f pose = new Matrix4f(poseStack.last().pose()).mul(undo);
            Matrix3f normal = new Matrix3f(poseStack.last().normal()).mul(new Matrix3f(undo));
            Vector3f place = new Vector3f();
            Vector3f facing = new Vector3f();

            for (Part part : this.parts) {
                if (part.texture == null) {
                    continue;
                }

                RenderType type = renderTypes.apply(part.texture);

                if (type == null) {
                    continue;
                }

                int out = type.mode() == VertexFormat.Mode.TRIANGLES ? 3 : type.mode() == VertexFormat.Mode.QUADS ? 4 : 0;

                if (out == 0) {
                    continue;
                }

                VertexConsumer consumer = buffer.getBuffer(type);
                int[] order = order(part.corners, out);

                for (int first = 0; first + part.corners <= part.count; first += part.corners) {
                    for (int corner : order) {
                        int at = (first + corner) * Part.STRIDE;
                        float[] data = part.data;
                        pose.transformPosition(data[at], data[at + 1], data[at + 2], place);
                        normal.transform(data[at + 3], data[at + 4], data[at + 5], facing);

                        if (facing.lengthSquared() > 1.0e-8F) {
                            facing.normalize();
                        }

                        drawing.draw(consumer, place.x, place.y, place.z, facing.x, facing.y, facing.z, light,
                                red * data[at + 8], green * data[at + 9], blue * data[at + 10], alpha * data[at + 11],
                                data[at + 6], data[at + 7], OverlayTexture.NO_OVERLAY);
                    }
                }
            }
        }

        /** Which corners of a shape of so many make up the shapes of so many the type draws. */
        private static int[] order(int corners, int out) {
            if (corners == out) {
                return corners == 4 ? new int[] { 0, 1, 2, 3 } : new int[] { 0, 1, 2 };
            }

            // A square as two triangles, a triangle as a square with its last corner twice.
            return corners == 4 ? new int[] { 0, 1, 2, 2, 3, 0 } : new int[] { 0, 1, 2, 2 };
        }

        /** Kept as the body is drawn. */
        static final class Builder {
            private final Matrix4f fromScreen;
            private final Matrix3f fromScreenNormal;
            @Nullable
            private final ResourceLocation ownTexture;
            private final Map<RenderType, Part> parts = new LinkedHashMap<>();
            private int vertices;

            Builder(Matrix4f pose, Matrix3f normal, @Nullable ResourceLocation ownTexture) {
                this.fromScreen = new Matrix4f(pose).invert();
                this.fromScreenNormal = new Matrix3f(normal).invert();
                this.ownTexture = ownTexture;
            }

            /** Where the vertices drawn with this type are kept; null for a type that is not kept. */
            @Nullable
            Part partFor(RenderType type) {
                Part part = this.parts.get(type);

                if (part == null && !this.parts.containsKey(type)) {
                    int corners = type.mode() == VertexFormat.Mode.QUADS ? 4 : type.mode() == VertexFormat.Mode.TRIANGLES ? 3 : 0;
                    ResourceLocation texture = corners == 0 ? null : textureOf(type);
                    part = corners == 0 ? null : new Part(this, corners, texture != null ? texture : this.ownTexture, texture == null);
                    this.parts.put(type, part);
                }

                return part;
            }

            boolean room() {
                return this.vertices < MOST_VERTICES;
            }

            Picture build() {
                List<Part> kept = new ArrayList<>();
                int guessed = 0;
                int vertices = 0;
                int leftOut = 0;

                for (Part part : this.parts.values()) {
                    // A part without normals is not of the body: a name above
                    // it, the shine of an enchantment, an outline.
                    if (part != null && part.count >= part.corners && part.normals) {
                        leftOut += part.settle();

                        if (part.count > 0) {
                            kept.add(part);
                            guessed += part.guessed ? 1 : 0;
                            vertices += part.count;
                        }
                    }
                }

                return new Picture(kept, vertices, guessed, leftOut);
            }
        }
    }

    /** The vertices of one render type: place, normal, texture place and colour, a dozen numbers each. */
    static final class Part {
        static final int STRIDE = 12;

        private final Picture.Builder owner;
        final int corners;
        @Nullable
        final ResourceLocation texture;
        final boolean guessed;
        float[] data = new float[STRIDE * 256];
        int count;
        boolean normals;
        private final Vector3f scratch = new Vector3f();

        Part(Picture.Builder owner, int corners, @Nullable ResourceLocation texture, boolean guessed) {
            this.owner = owner;
            this.corners = corners;
            this.texture = texture;
            this.guessed = guessed;
        }

        /** A new vertex, at a place on screen; what follows is about it. Returns false once there is no more room. */
        boolean start(float x, float y, float z) {
            if (!this.owner.room()) {
                return false;
            }

            if ((this.count + 1) * STRIDE > this.data.length) {
                this.data = java.util.Arrays.copyOf(this.data, this.data.length * 2);
            }

            int at = this.count * STRIDE;
            this.owner.fromScreen.transformPosition(x, y, z, this.scratch);
            this.data[at] = this.scratch.x;
            this.data[at + 1] = this.scratch.y;
            this.data[at + 2] = this.scratch.z;
            this.data[at + 3] = 0.0F;
            this.data[at + 4] = 1.0F;
            this.data[at + 5] = 0.0F;
            this.data[at + 6] = 0.0F;
            this.data[at + 7] = 0.0F;
            this.data[at + 8] = 1.0F;
            this.data[at + 9] = 1.0F;
            this.data[at + 10] = 1.0F;
            this.data[at + 11] = 1.0F;
            this.count++;
            this.owner.vertices++;
            return true;
        }

        void color(int red, int green, int blue, int alpha) {
            if (this.count > 0) {
                int at = (this.count - 1) * STRIDE;
                this.data[at + 8] = red / 255.0F;
                this.data[at + 9] = green / 255.0F;
                this.data[at + 10] = blue / 255.0F;
                this.data[at + 11] = alpha / 255.0F;
            }
        }

        void uv(float u, float v) {
            if (this.count > 0) {
                int at = (this.count - 1) * STRIDE;
                this.data[at + 6] = u;
                this.data[at + 7] = v;
            }
        }

        void normal(float x, float y, float z) {
            if (this.count > 0) {
                int at = (this.count - 1) * STRIDE;
                this.owner.fromScreenNormal.transform(x, y, z, this.scratch);
                this.data[at + 3] = this.scratch.x;
                this.data[at + 4] = this.scratch.y;
                this.data[at + 5] = this.scratch.z;
                this.normals = true;
            }
        }

        /**
         * Keeps whole shapes only, and of those the ones that can be seen:
         * a shape with no area - a face of a cube flat in one direction, or
         * of a bone scaled to nothing - draws nothing, nor does one whose
         * corners are all clear. Normals are made of unit length. Returns
         * how many vertices went.
         */
        int settle() {
            int whole = this.count - this.count % this.corners;
            float[] data = this.data;
            int kept = 0;

            for (int first = 0; first < whole; first += this.corners) {
                if (!seen(data, first, this.corners)) {
                    continue;
                }

                if (kept != first) {
                    System.arraycopy(data, first * STRIDE, data, kept * STRIDE, this.corners * STRIDE);
                }

                for (int corner = 0; corner < this.corners; corner++) {
                    int at = (kept + corner) * STRIDE;
                    float length = (float) Math.sqrt(data[at + 3] * data[at + 3] + data[at + 4] * data[at + 4] + data[at + 5] * data[at + 5]);

                    if (length > 1.0e-6F) {
                        data[at + 3] /= length;
                        data[at + 4] /= length;
                        data[at + 5] /= length;
                    }
                }

                kept += this.corners;
            }

            int gone = this.count - kept;
            this.count = kept;
            this.data = java.util.Arrays.copyOf(data, kept * STRIDE);
            return gone;
        }

        private static boolean seen(float[] data, int first, int corners) {
            boolean clear = true;

            for (int corner = 0; corner < corners && clear; corner++) {
                clear = data[(first + corner) * STRIDE + 11] < 0.004F;
            }

            if (clear) {
                return false;
            }

            // Twice the area of the triangles the shape is made of, squared, in blocks.
            int a = first * STRIDE;
            return area(data, a, a + STRIDE, a + 2 * STRIDE) > 1.0e-14F
                    || corners == 4 && area(data, a, a + 2 * STRIDE, a + 3 * STRIDE) > 1.0e-14F;
        }

        private static float area(float[] data, int a, int b, int c) {
            float ux = data[b] - data[a];
            float uy = data[b + 1] - data[a + 1];
            float uz = data[b + 2] - data[a + 2];
            float vx = data[c] - data[a];
            float vy = data[c + 1] - data[a + 1];
            float vz = data[c + 2] - data[a + 2];
            float x = uy * vz - uz * vy;
            float y = uz * vx - ux * vz;
            float z = ux * vy - uy * vx;
            return x * x + y * y + z * z;
        }
    }
}
