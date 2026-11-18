package com.argorice.epicysm.client;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import com.argorice.epicysm.EpicYsm;
import com.argorice.epicysm.client.convert.ConvertedModel;
import com.argorice.epicysm.client.convert.ModelConverter;
import com.argorice.epicysm.client.model.ModelScanner;
import com.argorice.epicysm.client.model.YsmModelSource;

/**
 * Client-side registry of discovered and converted models, plus the local
 * per-player detection of YSM's own model choice. Everything here runs on
 * the render thread.
 */
public final class ModelManager {
    private static final ModelManager INSTANCE = new ModelManager();

    public static ModelManager get() {
        return INSTANCE;
    }

    /** Frames a not-yet-uploaded foreign texture is retried for. */
    private static final int MAX_UNREADABLE_ATTEMPTS = 20;

    /** How many converted models (and their GPU textures) stay resident. */
    private static final int MAX_CACHED_MODELS = 24;

    /** How long a seen foreign renderer keeps counting after the last frame. */
    private static final int FOREIGN_RENDERER_MEMORY_TICKS = 10;

    /** How often per-player state is purged of players who left. */
    private static final int CLEANUP_INTERVAL_FRAMES = 600;

    private volatile Map<String, YsmModelSource> sources = null;

    /**
     * The scan of the model folders and the hashing of every picture in
     * them, done ahead on a thread of its own while the game is still
     * loading. Done on demand instead, it fell on the frame the first
     * player in a Yes Steve Model model was drawn - every model's pictures
     * decoded then and there - and on every join after a reload.
     */
    @Nullable
    private volatile Thread warming;

    /**
     * Converted models, most recently used last. A converted model owns a
     * registered GPU texture, so a large library must not keep every model
     * it ever showed: the least recently used ones are released and simply
     * re-converted (tens of milliseconds) if they come back.
     */
    private final Map<String, ConvertedModel> converted =
            new java.util.LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ConvertedModel> eldest) {
                    if (this.size() <= MAX_CACHED_MODELS) {
                        return false;
                    }

                    // Freeing the texture here would close it in the middle
                    // of a frame that may still be drawing with it, which
                    ModelManager.this.pendingRelease.add(eldest.getValue());
                    com.argorice.epicysm.client.Diag.info("Evicted converted model {} (cache limit {})", eldest.getKey(), MAX_CACHED_MODELS);
                    return true;
                }
            };

    private final Set<String> failed = new HashSet<>();
    private final Map<java.util.UUID, Integer> foreignRendererSeen = new HashMap<>();
    private final Map<java.util.UUID, Long> foreignSignatures = new HashMap<>();
    private final Map<java.util.UUID, Integer> unreadableAttempts = new HashMap<>();
    private final Map<java.util.UUID, Detection> probedModels = new HashMap<>();
    /** The texture Yes Steve Model is drawing each player with. */
    private final Map<java.util.UUID, ResourceLocation> foreignTextures = new HashMap<>();

    /**
     * What each player has chosen in Yes Steve Model, as Yes Steve Model
     * keeps it on the player, read once a second. A change of model is
     * otherwise noticed by the texture changing under the renderer, and
     * Yes Steve Model may put the new model's picture into the very
     * texture the old one used; then nothing changed as far as the texture
     * said, and the old model went on being fought in.
     */
    private final Map<java.util.UUID, String> selections = new HashMap<>();
    private final Map<java.util.UUID, Integer> selectionChecked = new HashMap<>();
    private static final int SELECTION_CHECK_TICKS = 20;

    /** The texture signature each player's overlay was last dropped for: dropped once per change, not once per frame. */
    private final Map<java.util.UUID, Long> forgottenFor = new HashMap<>();

    /** The texture signature last seen under each player, readable or not. */
    private final Map<java.util.UUID, Long> seenSignatures = new HashMap<>();

    /**
     * Game time at which a texture that stayed unreadable is matched
     * again. A model another player wears may not be on the card for a
     * while after its texture is first named; given up on for good, a
     * readable model was fought in through the overlay instead of being
     * converted.
     */
    private final Map<java.util.UUID, Long> matchAgainAt = new HashMap<>();
    private static final long MATCH_AGAIN_TICKS = 100L;

    /**
     * How many frames the live skeleton has been asked for and not found.
     * Generous: the model is built once, and until it is there is nothing
     * to compare against, but a walk of YSM's objects is not free either.
     */
    private static final int MAX_SKELETON_FRAMES = 60;
    private static final int SKELETON_EVERY = 10;
    private final Map<java.util.UUID, Integer> skeletonAttempts = new HashMap<>();
    /** Players Epic Fight is currently drawing itself, by tick last seen. */
    private final Map<java.util.UUID, Integer> epicFightDrew = new HashMap<>();
    /** Players handed to another mod's renderer, behind this mod's, by tick last seen. */
    private final Map<java.util.UUID, Integer> handedOver = new HashMap<>();
    private final Map<java.util.UUID, Boolean> yielded = new HashMap<>();
    private final java.util.List<ConvertedModel> pendingRelease = new java.util.ArrayList<>();
    private int observedFrames;
    private Object lastForeignRenderer;
    private ResourceLocation lastForeignTexture;
    private final YsmTextureMatcher textureMatcher = new YsmTextureMatcher();

    private ModelManager() {
    }

    /**
     * Called for every rendered player, every frame, from the vanilla
     * render event - before Epic Fight can cancel it. This is the only
     * observation point that keeps working while Epic Fight is told not to
     * render the player (see shouldYieldToYsm).
     */
    @SuppressWarnings("unchecked")
    public void observeRenderer(AbstractClientPlayer player, Object renderer) {
        // Preview dummies (the model picker draws throwaway players with a
        // fresh UUID each time) are not worth tracking: they would fill the
        // per-player maps for the rest of the session.
        if (!isRealWorldPlayer(player)) {
            return;
        }

        if (++this.observedFrames % CLEANUP_INTERVAL_FRAMES == 0) {
            this.forgetGonePlayers();
        }

        this.releasePending();
        this.noteBody(player);

        if (renderer instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer) {
            // The vanilla renderer: no other mod owns this player's model.
            this.foreignRendererSeen.remove(player.getUUID());
            return;
        }

        this.watchSelection(player);

        if (!(renderer instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer)) {
            return;
        }

        this.foreignRendererSeen.put(player.getUUID(), player.tickCount);

        try {
            var livingRenderer = (net.minecraft.client.renderer.entity.LivingEntityRenderer<AbstractClientPlayer, ?>) renderer;
            ResourceLocation foreignTexture = livingRenderer.getTextureLocation(player);

            if (player == Minecraft.getInstance().player) {
                this.lastForeignRenderer = renderer;
                this.lastForeignTexture = foreignTexture;
            }

            this.noteForeignRenderer(player, renderer, foreignTexture);
        } catch (Throwable ignored) {
            // A foreign renderer may not be ready to answer; skip the hint.
        }
    }

    /**
     * Notices a player choosing another model in Yes Steve Model, whatever
     * the texture under the renderer does, and has the model read again.
     * The choice is read off the player's own data, which Yes Steve Model
     * syncs to every client; that is a save of the entity to a tag, so not
     * every frame.
     */
    private void watchSelection(AbstractClientPlayer player) {
        java.util.UUID id = player.getUUID();
        Integer checked = this.selectionChecked.get(id);

        // A player who left and came back is a new entity, counting from nought again.
        if (checked != null && player.tickCount >= checked && player.tickCount - checked < SELECTION_CHECK_TICKS) {
            return;
        }

        this.selectionChecked.put(id, player.tickCount);
        com.argorice.epicysm.client.ysm.YsmSelection.Selection now = com.argorice.epicysm.client.ysm.YsmSelection.of(player);
        String key = now == null ? "" : now.modelId() + "|" + now.texture() + "|" + now.disabled();
        String before = this.selections.put(id, key);

        if (before == null || before.equals(key) || key.isEmpty()) {
            return;
        }

        com.argorice.epicysm.client.Diag.info("Player {} chose another model in Yes Steve Model ({}, was {}); it is read again",
                player.getName().getString(), key, before);
        this.foreignSignatures.remove(id);
        this.unreadableAttempts.remove(id);
        this.skeletonAttempts.remove(id);
        com.argorice.epicysm.client.ysm.YsmSkeletonOverlay.forget(id);
    }

    /**
     * The body each player was last seen in. A player who comes back into
     * view, dies or changes dimension is a new entity counting its ticks
     * from nought, and the ticks kept for the body before - when Epic Fight
     * last drew it, when Yes Steve Model did - lie ahead of the new one's.
     * "Drawn by Epic Fight within the last ten ticks" then held for as long
     * as the new body took to reach the old one's count, minutes or hours:
     * a player once fought beside in a converted model, met again in an
     * encrypted one, was not posed for all that time, whatever model they
     * changed to. What was kept by the tick goes with the body it was kept
     * for.
     */
    private final Map<java.util.UUID, java.lang.ref.WeakReference<AbstractClientPlayer>> bodies = new HashMap<>();

    private void noteBody(AbstractClientPlayer player) {
        java.util.UUID id = player.getUUID();
        java.lang.ref.WeakReference<AbstractClientPlayer> known = this.bodies.get(id);

        if (known != null && known.get() == player) {
            return;
        }

        this.bodies.put(id, new java.lang.ref.WeakReference<>(player));

        if (known != null) {
            this.epicFightDrew.remove(id);
            this.handedOver.remove(id);
            this.foreignRendererSeen.remove(id);
            this.selectionChecked.remove(id);

            // And the skeleton being posed was the old body's: Yes Steve
            // Model keeps one per entity. Left to be found out - by its
            // going still, a few hundred draws later - the new body fought
            // in Yes Steve Model's own animation for its first seconds.
            com.argorice.epicysm.client.ysm.YsmSkeletonOverlay.forget(id);
        }
    }

    /** Whether a tick kept for this player is one of this body's last few. */
    private static boolean lately(AbstractClientPlayer player, @Nullable Integer tick) {
        return tick != null && player.tickCount >= tick && player.tickCount - tick <= FOREIGN_RENDERER_MEMORY_TICKS;
    }

    /** Epic Fight has just drawn this player with a converted model. */
    public void noteEpicFightRendered(AbstractClientPlayer player) {
        this.epicFightDrew.put(player.getUUID(), player.tickCount);
    }

    /** Another mod's renderer, the one behind this mod's, has just drawn this player as something else. */
    public void noteHandedOver(AbstractClientPlayer player) {
        this.handedOver.put(player.getUUID(), player.tickCount);
    }

    /** Whether Epic Fight, not Yes Steve Model, is drawing this player. */
    public boolean epicFightDraws(AbstractClientPlayer player) {
        if (lately(player, this.epicFightDrew.get(player.getUUID()))) {
            return true;
        }

        // A player handed to another mod - a transformation - counts only
        // while that mod still owns the look. Remembered for a few ticks
        // like the rest, the model came back from a transformation posed
        // by Yes Steve Model alone for half a second before Epic Fight's
        // animation was put on it again.
        return lately(player, this.handedOver.get(player.getUUID()))
                && com.argorice.epicysm.client.compat.LookOwners.ownsLook(player);
    }

    /** The live YSM renderer of the local player, for the inspector. */
    @Nullable
    public Object lastForeignRenderer() {
        return this.lastForeignRenderer;
    }

    @Nullable
    public ResourceLocation lastForeignTexture() {
        return this.lastForeignTexture;
    }

    /**
     * Whether this is a player actually present in the world, as opposed to
     * a client-side stand-in another mod renders in a menu.
     */
    private static boolean isRealWorldPlayer(AbstractClientPlayer player) {
        try {
            var level = Minecraft.getInstance().level;
            return level != null && level.getEntity(player.getId()) == player;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether Epic Fight should leave this player to Yes Steve Model. */
    public boolean shouldYieldToYsm(AbstractClientPlayer player) {
        if (com.argorice.epicysm.client.compat.LookOwners.ownsLook(player)) {
            // Another mod is drawing this player as something else.
            return false;
        }

        // A gun in hand, a bow drawn, an emote, sleep: the body is Yes
        // Steve Model's to animate for the moment, as out of battle.
        if (com.argorice.epicysm.client.compat.LeftToYsm.now(player)) {
            return true;
        }

        // A model this mod could convert, left to Yes Steve Model all the
        // same: its layers are only right when Yes Steve Model draws them.
        Detection readable = this.drawnByYsm(player) ? this.probedModels.get(player.getUUID()) : null;

        if (readable != null && this.ysmDraws(player, readable)) {
            return true;
        }

        if (EpicYsmConfig.unreadableModels() == EpicYsmConfig.Unreadable.KEEP_COMBAT) {
            // The player chose Epic Fight animations over the model itself.
            return false;
        }

        if (!lately(player, this.foreignRendererSeen.get(player.getUUID()))) {
            this.yielded.remove(player.getUUID());
            return false;
        }

        Detection detection = this.probedModels.get(player.getUUID());
        boolean yield = detection == null || this.convertedModel(detection, this.foreignTextures.get(player.getUUID())) == null;

        if (yield != this.yielded.getOrDefault(player.getUUID(), Boolean.FALSE)) {
            this.yielded.put(player.getUUID(), yield);

            if (yield) {
                if (EpicYsmConfig.skeletonOverlay()) {
                    EpicYsm.LOGGER.info("Model of player {} cannot be read (encrypted .ysm); Yes Steve Model draws it, and Epic"
                            + " Fight poses it through Yes Steve Model's own skeleton once that is found. Change this in the"
                            + " settings screen (/epicysm config)", player.getName().getString());
                } else {
                    EpicYsm.LOGGER.info("Model of player {} cannot be read (encrypted .ysm); leaving it to Yes Steve Model, so"
                            + " Epic Fight attack animations are not shown for it. Change this in the settings screen (/epicysm config)",
                            player.getName().getString());
                }

                // Yes Steve Model has this model unpacked in memory in order
                // to draw it. Write down the shape of those objects once, so
                if (Diag.on()) {
                    com.argorice.epicysm.client.ysm.YsmGeometryProbe.runOnce(player, this.lastForeignRenderer, this.lastForeignTexture);
                }
            }
        }

        return yield;
    }

    /** Forgets players that left the world, so nothing accumulates. */
    private void forgetGonePlayers() {
        var level = Minecraft.getInstance().level;

        if (level == null) {
            return;
        }

        this.foreignRendererSeen.keySet().removeIf(uuid -> level.getPlayerByUUID(uuid) == null);

        // Asked about every player drawn, a preview on a screen included,
        // and kept by id; nothing took an answer out again.
        com.argorice.epicysm.client.compat.LookOwners.forgetIf(uuid -> level.getPlayerByUUID(uuid) == null);

        // An overlay belongs to a player or to a maid; whichever it is, it
        // goes when the entity is no longer in the world.
        Set<java.util.UUID> overlaid = com.argorice.epicysm.client.ysm.YsmSkeletonOverlay.players();
        Set<java.util.UUID> present = new HashSet<>();

        for (java.util.UUID uuid : overlaid) {
            if (level.getPlayerByUUID(uuid) != null) {
                present.add(uuid);
            }
        }

        boolean entitiesKnown = true;

        if (present.size() < overlaid.size()) {
            try {
                for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
                    if (overlaid.contains(entity.getUUID())) {
                        present.add(entity.getUUID());
                    }
                }
            } catch (Throwable ignored) {
                // The entity list could not be read; nothing is forgotten this time.
                entitiesKnown = false;
            }
        }

        if (entitiesKnown) {
            for (java.util.UUID uuid : overlaid) {
                if (!present.contains(uuid)) {
                    com.argorice.epicysm.client.ysm.YsmSkeletonOverlay.forget(uuid);
                }
            }
        }

        this.foreignSignatures.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.saidTexture.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.unreadableAttempts.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.probedModels.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.yielded.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.handedOver.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.epicFightDrew.keySet().retainAll(this.foreignRendererSeen.keySet());

        // The body is forgotten with the rest, not when it is collected: a
        // player back in the world and not yet looked at would otherwise
        // be taken for one never seen, and the ticks kept for the body
        // before would stay.
        this.bodies.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.selections.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.selectionChecked.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.forgottenFor.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.seenSignatures.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.matchAgainAt.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.saidDrawnBy.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.saidFellBack.keySet().retainAll(this.foreignRendererSeen.keySet());
    }

    private static long gameTime() {
        try {
            var level = Minecraft.getInstance().level;
            return level == null ? 0L : level.getGameTime();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * Records which texture another mod's player renderer (YSM) used for
     * this player. When it changes (the player picked another model in
     * YSM's own UI), the model is identified again.
     */
    public void noteForeignRenderer(AbstractClientPlayer player, Object renderer, @Nullable ResourceLocation texture) {
        if (texture == null) {
            return;
        }

        this.foreignTextures.put(player.getUUID(), texture);

        // The signature covers the path AND the texture object behind it:
        // YSM may re-register a different image under the same textures/N
        // id, which must re-trigger detection just like a path change.
        long signature = texture.hashCode();

        try {
            var registered = Minecraft.getInstance().getTextureManager().getTexture(texture);

            if (registered != null) {
                signature = signature * 31L + System.identityHashCode(registered);
                signature = signature * 31L + registered.getId();
            }
        } catch (Throwable ignored) {
        }

        Long previous = this.foreignSignatures.get(player.getUUID());

        if (previous != null && previous == signature) {
            Long again = this.matchAgainAt.get(player.getUUID());

            // Not yet - unless the clock has gone back by more than the
            // wait itself, which is another world's clock, not this one's.
            if (again == null || gameTime() < again && again - gameTime() <= MATCH_AGAIN_TICKS) {
                return;
            }

            this.matchAgainAt.remove(player.getUUID());
        }

        // Another texture than the last frame's: its unreadable frames are
        // counted from nought, not from where the last texture's left off.
        Long lastSeen = this.seenSignatures.put(player.getUUID(), signature);

        if (lastSeen == null || lastSeen != signature) {
            this.unreadableAttempts.remove(player.getUUID());
        }

        if (!this.unreadableAttempts.containsKey(player.getUUID())) {
            com.argorice.epicysm.client.Diag.info("Player {} is rendered by another mod with texture {}", player.getName().getString(), texture);
        }

        // Yes Steve Model builds the model afresh when it is selected, so
        // the bone objects found last time belong to the previous copy of
        // the model. Dropped once per change of texture: dropped on every
        // frame the texture stayed unreadable, as it was, the overlay was
        // built anew each of those frames, a walk of Yes Steve Model's
        // objects every time.
        Long forgotten = this.forgottenFor.put(player.getUUID(), signature);

        if (forgotten == null || forgotten != signature) {
            com.argorice.epicysm.client.ysm.YsmSkeletonOverlay.forget(player.getUUID());
        }

        // Primary signal: the pixels of the texture the foreign renderer
        // draws with, matched against the model textures on disk. This
        // follows YSM's own selection (Alt+Y) exactly.
        this.awaitWarmUp();
        YsmTextureMatcher.Match match = this.textureMatcher.match(texture, this.sources());

        if (!match.readable()) {
            // The texture has not been uploaded yet. Retry on later frames
            // instead of caching the failure, but only a few times: a full
            // readback is expensive and some textures never become readable.
            int attempts = this.unreadableAttempts.merge(player.getUUID(), 1, Integer::sum);

            if (attempts <= MAX_UNREADABLE_ATTEMPTS) {
                return;
            }

            this.matchAgainAt.put(player.getUUID(), gameTime() + MATCH_AGAIN_TICKS);
            com.argorice.epicysm.client.Diag.info("Foreign texture {} stayed unreadable after {} attempts; keeping the default model",
                    texture, attempts - 1);
        }

        // Still unreadable, the count stays past its limit: the next look,
        // in a while, is one look and not twenty more.
        if (match.readable()) {
            this.unreadableAttempts.remove(player.getUUID());
        }

        Detection detection = match.modelId().isEmpty() ? null : new Detection(match.modelId(), match.texturePath());

        // Several models ship the same picture: before walking Yes Steve
        // Model's objects for the skeleton, ask what the player actually
        // chose - Yes Steve Model keeps the choice on the player, synced.
        if (detection == null && !match.candidates().isEmpty()) {
            detection = this.settleBySelection(player, match.candidates());
        }

        if (detection == null && !match.candidates().isEmpty()) {
            // Walking YSM's objects costs a good fraction of a frame, so it
            // is not done on every one of them while waiting.
            int seen = this.skeletonAttempts.getOrDefault(player.getUUID(), 0);

            if (seen % SKELETON_EVERY != 0 && seen < MAX_SKELETON_FRAMES) {
                this.skeletonAttempts.merge(player.getUUID(), 1, Integer::sum);
                return;
            }

            detection = this.settleBySkeleton(player, renderer, match.candidates());

            // On the very first frame a player is drawn, Yes Steve Model has
            // not finished building the model yet and its skeleton is not
            if (detection == null) {
                int frames = this.skeletonAttempts.merge(player.getUUID(), 1, Integer::sum);

                if (frames < MAX_SKELETON_FRAMES) {
                    return;
                }

                com.argorice.epicysm.client.Diag.info("Yes Steve Model's skeleton stayed unreadable after {} frames; the picture is"
                        + " shared by several models and none of them is assumed", frames);
            }
        }

        this.skeletonAttempts.remove(player.getUUID());
        this.foreignSignatures.put(player.getUUID(), signature);

        if (detection == null) {
            String probed = YsmModelProbe.probe(renderer, player.getUUID(), texture, this.probeCandidates());

            if (!probed.isEmpty()) {
                detection = new Detection(probed, "");
            }
        }

        if (detection == null) {
            this.probedModels.remove(player.getUUID());
        } else {
            this.probedModels.put(player.getUUID(), detection);
            EpicYsm.LOGGER.info("Detected model '{}' for player {}", detection.modelId(), player.getName().getString());
        }
    }

    /**
     * A model plus the exact texture file YSM is showing it with (empty
     * when unknown, in which case the model's default texture is used).
     */
    private record Detection(String modelId, String texturePath, String key) {
        Detection(String modelId, String texturePath) {
            this(modelId, texturePath, texturePath.isEmpty() ? modelId : modelId + "|" + texturePath);
        }

        /** Built once with the record: it is asked for several times a frame. */
        String cacheKey() {
            return this.key;
        }

        /**
         * The key a conversion is stored under. It carries the size Yes
         * Steve Model was measured drawing this model at, so a model that
         * was converted at a guessed size is quietly rebuilt at the real
         * one the moment the measurement lands, instead of staying wrong
         */
        String cacheKey(float[] scale) {
            return scale == null ? this.cacheKey()
                    : this.cacheKey() + "@" + Math.round(scale[0] * 1000.0F) + "x" + Math.round(scale[1] * 1000.0F);
        }
    }

    /** Sanitized id or display name -> model id, for the probe; made once with the models and kept. */
    @Nullable
    private volatile Map<String, String> probeNames;

    /**
     * What a model's own files say of its skeleton: every bone's name, the
     * bone above each, and how many of them make up a glowing layer - the
     * bones Yes Steve Model draws at full brightness, named ysmGlow.
     */
    public record Shape(String modelId, java.util.Set<String> names, Map<String, String> above, int glowing) {
    }

    private static final Shape NO_SHAPE = new Shape("", java.util.Set.of(), Map.of(), 0);

    /** model id -> its shape, read once; also from the thread that reads the models ahead. */
    private final Map<String, Shape> shapes = new java.util.concurrent.ConcurrentHashMap<>();

    private Shape shapeOf(String modelId) {
        Shape known = this.shapes.get(modelId);

        if (known != null) {
            return known;
        }

        Shape shape = NO_SHAPE;
        YsmModelSource source = this.sources().get(modelId);

        if (source != null) {
            try {
                var geometry = source.readMainModel().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
                java.util.Set<String> names = new java.util.LinkedHashSet<>();
                Map<String, String> above = new HashMap<>();
                int glowing = 0;

                if (geometry.has("bones")) {
                    for (var element : geometry.getAsJsonArray("bones")) {
                        var bone = element.getAsJsonObject();

                        if (!bone.has("name")) {
                            continue;
                        }

                        String name = bone.get("name").getAsString();
                        names.add(name);

                        if (bone.has("parent")) {
                            above.put(name, bone.get("parent").getAsString());
                        }

                        if (name.toLowerCase(java.util.Locale.ROOT).startsWith("ysmglow")) {
                            glowing++;
                        }
                    }
                }

                shape = new Shape(modelId, java.util.Set.copyOf(names), Map.copyOf(above), glowing);
            } catch (Throwable ignored) {
                // A model whose geometry will not parse has no shape to
                // offer; it is treated as one nothing is known about.
            }
        }

        this.shapes.put(modelId, shape);
        return shape;
    }

    /**
     * The shape of the model this player wears as its own files give it,
     * or null for a model this mod cannot read. Yes Steve Model hands over
     * only part of a model's tree; the files have all of it.
     */
    @Nullable
    public Shape shapeFor(java.util.UUID player) {
        Detection detection = this.probedModels.get(player);

        if (detection == null) {
            return null;
        }

        Shape shape = this.shapeOf(detection.modelId());
        return shape.names().isEmpty() ? null : shape;
    }

    /** The readable model each player was last said to have drawn by Yes Steve Model, and the one said to have fallen back. */
    private final Map<java.util.UUID, Detection> saidDrawnBy = new HashMap<>();
    private final Map<java.util.UUID, Detection> saidFellBack = new HashMap<>();

    /**
     * Whether a model this mod can read is left to Yes Steve Model to draw
     * in battle, posed by Epic Fight through the model's live skeleton the
     * way an encrypted model is.
     *
     * A converted mesh is the model in the state its files start in: what
     * the player switched in the model's own menu, the expression on its
     * face, a layer that glows - none of that is in the mesh. Drawn by Yes
     * Steve Model, every layer is in the state it is in out of battle.
     * Should the live skeleton turn out not to be posable, the converted
     * mesh is what fights after all.
     */
    private boolean ysmDraws(AbstractClientPlayer player, Detection detection) {
        EpicYsmConfig.OpenModels mode = EpicYsmConfig.openModels();

        if (mode == EpicYsmConfig.OpenModels.CONVERTED || !EpicYsmConfig.skeletonOverlay()
                || com.argorice.epicysm.client.ysm.YsmRenderBridge.disabled()) {
            return false;
        }

        int glowing = this.shapeOf(detection.modelId()).glowing();

        if (mode == EpicYsmConfig.OpenModels.AUTO && glowing == 0) {
            return false;
        }

        // Asked several times a frame; said once per model a player wears.
        boolean gaveUp = com.argorice.epicysm.client.ysm.YsmSkeletonOverlay.gaveUpOn(player.getUUID());

        if ((gaveUp ? this.saidFellBack : this.saidDrawnBy).put(player.getUUID(), detection) != detection) {
            if (gaveUp) {
                EpicYsm.LOGGER.info("Model '{}' on {} could not be posed through Yes Steve Model's own skeleton, so the"
                        + " converted mesh fights instead", detection.modelId(), player.getName().getString());
            } else {
                EpicYsm.LOGGER.info("Model '{}' on {} is drawn by Yes Steve Model in battle, every layer as it is out of"
                        + " battle, and posed by Epic Fight the way an encrypted model is ({}). The settings screen"
                        + " (/epicysm config) has it converted instead", detection.modelId(), player.getName().getString(),
                        mode == EpicYsmConfig.OpenModels.YSM ? "the settings say so for every readable model"
                                : "it has a glowing layer of " + glowing + " bone(s), which a converted mesh draws lit like the rest");
            }
        }

        return !gaveUp;
    }

    /** The model the player chose, by Yes Steve Model's own record of it, if it is one of those shipping the picture. */
    @Nullable
    private Detection settleBySelection(AbstractClientPlayer player, java.util.List<YsmTextureMatcher.Candidate> candidates) {
        com.argorice.epicysm.client.ysm.YsmSelection.Selection chosen = com.argorice.epicysm.client.ysm.YsmSelection.of(player);

        if (chosen == null) {
            return null;
        }

        for (YsmTextureMatcher.Candidate candidate : candidates) {
            if (candidate.modelId().equals(chosen.modelId())) {
                com.argorice.epicysm.client.Diag.info("Several models ship this picture; Yes Steve Model records '{}' as the one"
                        + " the player chose (texture '{}')", candidate.modelId(), chosen.texture());
                return new Detection(candidate.modelId(), candidate.texturePath());
            }
        }

        com.argorice.epicysm.client.Diag.info("Yes Steve Model records '{}' as the player's choice, which is none of the {} model(s)"
                + " shipping this picture; deciding by the skeleton instead", chosen.modelId(), candidates.size());
        return null;
    }

    /** Chooses between the models that ship the very same picture. */
    @Nullable
    private Detection settleBySkeleton(AbstractClientPlayer player, Object renderer,
                                       java.util.List<YsmTextureMatcher.Candidate> candidates) {
        java.util.List<com.argorice.epicysm.client.ysm.YsmLiveSkeleton.Skeleton> live =
                com.argorice.epicysm.client.ysm.YsmLiveSkeleton.read(player, renderer);

        if (live.isEmpty()) {
            com.argorice.epicysm.client.Diag.info("Several models ship the picture {} is drawn with and Yes Steve Model's live"
                    + " skeleton could not be read, so none of them is assumed", player.getName().getString());
            return null;
        }

        YsmTextureMatcher.Candidate best = null;
        double bestScore = 0.0;

        for (YsmTextureMatcher.Candidate candidate : candidates) {
            java.util.Set<String> ours = this.boneNamesOf(candidate.modelId());

            if (ours.isEmpty()) {
                continue;
            }

            for (var skeleton : live) {
                java.util.Set<String> theirs = skeleton.names();
                int shared = 0;

                for (String name : theirs) {
                    if (ours.contains(name)) {
                        shared++;
                    }
                }

                // Jaccard: a model whose bones are a subset of a bigger
                // one must not win on the strength of being small.
                double score = (double) shared / (ours.size() + theirs.size() - shared);

                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }

        if (best == null || bestScore < 0.75) {
            com.argorice.epicysm.client.Diag.info("Several models ship this picture and none of their skeletons matches the one"
                    + " Yes Steve Model is showing (best {}%), so none of them is assumed",
                    Math.round(bestScore * 100.0));
            return null;
        }

        com.argorice.epicysm.client.Diag.info("Several models ship this picture; '{}' is the one whose skeleton Yes Steve Model"
                + " actually has ({}% of the bones)", best.modelId(), Math.round(bestScore * 100.0));
        return new Detection(best.modelId(), best.texturePath());
    }

    /**
     * Bone names of a model's own geometry file: those its shape was read
     * with, ahead of time. Read again here, they cost a read of the whole
     * geometry of every model sharing a picture, on the frame a player
     * changed into one of them.
     */
    private java.util.Set<String> boneNamesOf(String modelId) {
        return this.shapeOf(modelId).names();
    }

    /**
     * Sanitized id or display name -> model id, for the probe. Each name is
     * in the model's own ysm.json, and reading them all opened every model
     * there is - a second and more of a frozen game in a large pack, on the
     * frame a player put on a model none of them is, an encrypted one. They
     * are read once, with the models ahead of time.
     */
    private Map<String, String> probeCandidates() {
        Map<String, String> known = this.probeNames;

        if (known != null) {
            return known;
        }

        Map<String, String> candidates = new HashMap<>();

        for (Map.Entry<String, YsmModelSource> entry : this.sources().entrySet()) {
            candidates.put(entry.getKey(), entry.getKey());
            String metadataName = entry.getValue().metadataNameSanitized();

            // Short sanitized names (a CJK display name collapses to "_")
            // would match everything; only distinctive names may identify.
            if (metadataName.length() >= 3 && !metadataName.replace("_", "").isEmpty()) {
                candidates.putIfAbsent(metadataName, entry.getKey());
            }
        }

        this.probeNames = Map.copyOf(candidates);
        return this.probeNames;
    }

    /**
     * The texture to draw this player's converted model with: the one Yes
     * Steve Model itself draws the player with, when it has one on the
     * card - the player's own skin on the Steve and Alex models, the
     * variant picked in its skin screen, a picture it put together itself
     * - and the model's own texture file otherwise. A converted model is
     * shared by every player wearing it, so this is the player's, not the
     * model's.
     */
    public ResourceLocation textureFor(AbstractClientPlayer player, ConvertedModel model) {
        Detection detection = this.probedModels.get(player.getUUID());
        ResourceLocation drawn = this.foreignTextures.get(player.getUUID());

        // A picture matched to one of the model's own files is that file,
        // registered already; what Yes Steve Model draws with is taken only
        // where none of them matched.
        if (detection == null || !detection.texturePath().isEmpty() || drawn == null || drawn.equals(model.texture())) {
            return model.texture();
        }

        try {
            // Only a texture that is actually on the card: asking the
            // texture manager for an unknown one would register a blank.
            if (Minecraft.getInstance().getTextureManager().getTexture(drawn, null) == null) {
                return model.texture();
            }
        } catch (Throwable t) {
            return model.texture();
        }

        ResourceLocation before = this.saidTexture.put(player.getUUID(), drawn);

        if (!drawn.equals(before)) {
            com.argorice.epicysm.client.Diag.info("Model {} on {} is drawn with the texture Yes Steve Model draws it with ({}),"
                    + " not the model's own file: the player's skin on a Steve or Alex model, or a variant picked in"
                    + " Yes Steve Model's skin screen", model.id(), player.getName().getString(), drawn);
        }

        return drawn;
    }

    /** The texture each player was last said to be drawn with, so the log says it once per change. */
    private final Map<java.util.UUID, ResourceLocation> saidTexture = new HashMap<>();

    /** The converted model to draw the given player with, or null for default rendering. */
    @Nullable
    public ConvertedModel modelFor(AbstractClientPlayer player) {
        if (com.argorice.epicysm.client.compat.LookOwners.ownsLook(player)) {
            return null;
        }

        // Only while Yes Steve Model is the one drawing this player. With
        // its model switched off in Yes Steve Model's own settings the
        // vanilla renderer draws the player, and the model read off Yes
        // Steve Model's renderer earlier is no longer the one on screen -
        // it was still fought in until the next clean-up came round.
        if (!this.drawnByYsm(player)) {
            return null;
        }

        // YSM's own selection (Alt+Y) is the single authority: whatever its
        // renderer shows for this player is what fights. If the texture
        Detection detection = this.probedModels.get(player.getUUID());

        if (detection == null || this.ysmDraws(player, detection)) {
            return null;
        }

        return this.convertedModel(detection, this.foreignTextures.get(player.getUUID()));
    }

    /** Whether Yes Steve Model's renderer drew this player within the last few ticks. */
    public boolean drawnByYsm(AbstractClientPlayer player) {
        return lately(player, this.foreignRendererSeen.get(player.getUUID()));
    }

    public synchronized Map<String, YsmModelSource> sources() {
        if (this.sources == null) {
            this.sources = ModelScanner.scan(Minecraft.getInstance().gameDirectory.toPath());
            EpicYsm.LOGGER.info("Found {} plain YSM model(s)", this.sources.size());
        }

        return Collections.unmodifiableMap(this.sources);
    }

    /** Starts reading the models and hashing their pictures ahead of time. */
    public void warmUp() {
        this.awaitWarmUp();
        Thread thread = new Thread(() -> {
            try {
                long start = System.nanoTime();
                Map<String, YsmModelSource> found = this.sources();
                int pictures = this.textureMatcher.prewarm(found);

                for (String id : found.keySet()) {
                    this.shapeOf(id);
                }

                this.probeCandidates();
                EpicYsm.LOGGER.info("Read {} model(s) and {} picture(s) of theirs ahead of time, in {} ms, so that the first"
                        + " player drawn in one of them costs no frame", found.size(), pictures,
                        (System.nanoTime() - start) / 1_000_000L);
            } catch (Throwable t) {
                EpicYsm.LOGGER.warn("Could not read the models ahead of time; they are read when first needed", t);
            }
        }, "EpicYSM model scan");
        thread.setDaemon(true);
        this.warming = thread;
        thread.start();
    }

    /** Waits for the reading ahead to finish, where its results are about to be used. */
    private void awaitWarmUp() {
        Thread thread = this.warming;

        if (thread == null || thread == Thread.currentThread()) {
            return;
        }

        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        this.warming = null;
    }

    @Nullable
    public ConvertedModel convertedModel(String modelId) {
        return this.convertedModel(new Detection(modelId, ""), null);
    }

    @Nullable
    private ConvertedModel convertedModel(Detection detection, @Nullable ResourceLocation foreignTexture) {
        float[] measured = null; // the size comes from ysm.json; see ModelConverter.computeRenderScale
        String key = detection.cacheKey(measured);
        ConvertedModel model = this.converted.get(key);

        if (model != null) {
            return model;
        }

        if (this.failed.contains(key)) {
            return null;
        }

        String modelId = detection.modelId();
        YsmModelSource source = this.sources().get(modelId);

        if (source == null) {
            this.failed.add(key);
            EpicYsm.LOGGER.warn("Model {} not found", modelId);
            return null;
        }

        try {
            ModelConverter.Result result = ModelConverter.convert(modelId, source, measured);
            ResourceLocation texture = registerTexture(key, source, detection.texturePath());
            model = new ConvertedModel(modelId, result.displayName(), result.mesh(), result.armature(), texture,
                    !result.allCutout() && hasTranslucency(source), result.physicsJoints(),
                    result.animatedJoints(), result.animation());
            this.converted.put(key, model);
            EpicYsm.LOGGER.info("Converted model {} ({})", modelId, result.displayName());
            return model;
        } catch (ModelConverter.NotHumanoid e) {
            // Expected for a cockroach or a truck, and not a fault: Yes
            // Steve Model draws it as it always did.
            this.failed.add(key);
            EpicYsm.LOGGER.info("Model {} is not shaped like a person, so Epic Fight cannot pose it: {}",
                    modelId, e.getMessage());
            return null;
        } catch (Exception e) {
            this.failed.add(key);
            EpicYsm.LOGGER.error("Failed to convert model {}", modelId, e);
            return null;
        }
    }

    /** Frees textures of evicted models, between frames rather than during one. */
    private void releasePending() {
        if (this.pendingRelease.isEmpty()) {
            return;
        }

        for (ConvertedModel model : this.pendingRelease) {
            releaseTexture(model);
        }

        this.pendingRelease.clear();
    }

    private static void releaseTexture(ConvertedModel model) {
        try {
            Minecraft.getInstance().getTextureManager().release(model.texture());
        } catch (Throwable t) {
            EpicYsm.LOGGER.warn("Failed to release the texture of model {}", model.id(), t);
        }
    }

    /** Drops all caches; models are re-scanned and re-converted on next use. */
    public void reload() {
        this.awaitWarmUp();
        this.releasePending();

        for (ConvertedModel model : this.converted.values()) {
            releaseTexture(model);
        }

        this.converted.clear();
        this.failed.clear();
        this.probedModels.clear();
        this.foreignSignatures.clear();
        this.foreignRendererSeen.clear();
        this.unreadableAttempts.clear();
        this.yielded.clear();
        this.textureMatcher.reset();
        com.argorice.epicysm.client.render.PhysicsAnimator.get().reset();
        com.argorice.epicysm.client.render.OwnAnimator.get().reset();
        com.argorice.epicysm.client.compat.LookOwners.resetAll();
        com.argorice.epicysm.client.ysm.YsmSkeletonOverlay.resetAll();

        // And the way into Yes Steve Model's render, if it was closed after
        // errors: a reload is the one thing short of a restart that asks
        // for everything to be tried again.
        com.argorice.epicysm.client.ysm.YsmRenderBridge.enable();
        this.probeNames = null;
        this.shapes.clear();
        this.saidDrawnBy.clear();
        this.saidFellBack.clear();
        this.sources = null;
        this.warmUp();
    }

    /**
     * Registers the model's texture. texturePath, when known, is the exact
     * file YSM is drawing with (a specific colour variant); otherwise the
     * model's declared default is used.
     */
    private static ResourceLocation registerTexture(String cacheKey, YsmModelSource source, String texturePath) throws IOException {
        byte[] data = texturePath.isEmpty() ? null : source.readEntry(texturePath);

        if (data == null) {
            data = source.readMainTexture();
        }

        if (data == null) {
            throw new IOException("Model " + source.id() + " has no usable texture");
        }

        NativeImage image = NativeImage.read(new java.io.ByteArrayInputStream(data));
        DynamicTexture texture = new DynamicTexture(image);
        String safeKey = cacheKey.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        ResourceLocation location = new ResourceLocation(EpicYsm.MODID, "dynamic/" + safeKey);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        return location;
    }

    private static boolean hasTranslucency(YsmModelSource source) {
        // Conservative default: cutout. Translucent rendering can be forced
        // per model via epicysm.json {"translucent": true}.
        JsonObject overrides = source.readOverrides();
        return overrides != null && overrides.has("translucent") && overrides.get("translucent").getAsBoolean();
    }
}
