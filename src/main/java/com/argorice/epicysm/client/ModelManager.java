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

    private Map<String, YsmModelSource> sources = null;

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
     * How many frames the live skeleton has been asked for and not found.
     * Generous: the model is built once, and until it is there is nothing
     * to compare against, but a walk of YSM's objects is not free either.
     */
    private static final int MAX_SKELETON_FRAMES = 60;
    private static final int SKELETON_EVERY = 10;
    private final Map<java.util.UUID, Integer> skeletonAttempts = new HashMap<>();
    /** Players Epic Fight is currently drawing itself, by tick last seen. */
    private final Map<java.util.UUID, Integer> epicFightDrew = new HashMap<>();
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

        if (renderer instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer) {
            // The vanilla renderer: no other mod owns this player's model.
            this.foreignRendererSeen.remove(player.getUUID());
            return;
        }

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

    /** Epic Fight has just drawn this player with a converted model. */
    public void noteEpicFightRendered(AbstractClientPlayer player) {
        this.epicFightDrew.put(player.getUUID(), player.tickCount);
    }

    /** Whether Epic Fight, not Yes Steve Model, is drawing this player. */
    public boolean epicFightDraws(AbstractClientPlayer player) {
        Integer tick = this.epicFightDrew.get(player.getUUID());
        return tick != null && player.tickCount - tick <= FOREIGN_RENDERER_MEMORY_TICKS;
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
            return level != null && level.getPlayerByUUID(player.getUUID()) == player;
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

        if (EpicYsmConfig.unreadableModels() == EpicYsmConfig.Unreadable.KEEP_COMBAT) {
            // The player chose Epic Fight animations over the model itself.
            return false;
        }

        Integer seenTick = this.foreignRendererSeen.get(player.getUUID());

        if (seenTick == null || player.tickCount - seenTick > FOREIGN_RENDERER_MEMORY_TICKS) {
            this.yielded.remove(player.getUUID());
            return false;
        }

        Detection detection = this.probedModels.get(player.getUUID());
        boolean yield = detection == null || this.convertedModel(detection, this.foreignTextures.get(player.getUUID())) == null;

        if (yield != this.yielded.getOrDefault(player.getUUID(), Boolean.FALSE)) {
            this.yielded.put(player.getUUID(), yield);

            if (yield) {
                EpicYsm.LOGGER.info("Model of player {} cannot be read (encrypted .ysm); leaving it to Yes Steve Model, so"
                        + " Epic Fight attack animations are not shown for it. Change this in the settings screen (/epicysm config)",
                        player.getName().getString());

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

        for (java.util.UUID uuid : com.argorice.epicysm.client.ysm.YsmSkeletonOverlay.players()) {
            if (level.getPlayerByUUID(uuid) == null) {
                com.argorice.epicysm.client.ysm.YsmSkeletonOverlay.forget(uuid);
            }
        }
        this.foreignSignatures.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.unreadableAttempts.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.probedModels.keySet().retainAll(this.foreignRendererSeen.keySet());
        this.yielded.keySet().retainAll(this.foreignRendererSeen.keySet());
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
            return;
        }

        if (!this.unreadableAttempts.containsKey(player.getUUID())) {
            com.argorice.epicysm.client.Diag.info("Player {} is rendered by another mod with texture {}", player.getName().getString(), texture);
        }

        // Yes Steve Model builds the model afresh when it is selected, so
        // the bone objects found last time belong to the previous copy of
        com.argorice.epicysm.client.ysm.YsmSkeletonOverlay.forget(player.getUUID());

        // Primary signal: the pixels of the texture the foreign renderer
        // draws with, matched against the model textures on disk. This
        // follows YSM's own selection (Alt+Y) exactly.
        YsmTextureMatcher.Match match = this.textureMatcher.match(texture, this.sources());

        if (!match.readable()) {
            // The texture has not been uploaded yet. Retry on later frames
            // instead of caching the failure, but only a few times: a full
            // readback is expensive and some textures never become readable.
            int attempts = this.unreadableAttempts.merge(player.getUUID(), 1, Integer::sum);

            if (attempts <= MAX_UNREADABLE_ATTEMPTS) {
                return;
            }

            com.argorice.epicysm.client.Diag.info("Foreign texture {} stayed unreadable after {} attempts; keeping the default model",
                    texture, attempts - 1);
        }

        this.unreadableAttempts.remove(player.getUUID());
        Detection detection = match.modelId().isEmpty() ? null : new Detection(match.modelId(), match.texturePath());

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
    private record Detection(String modelId, String texturePath) {
        String cacheKey() {
            return this.texturePath.isEmpty() ? this.modelId : this.modelId + "|" + this.texturePath;
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

    /** model id -> the bone names of its geometry on disk, read once. */
    private final Map<String, java.util.Set<String>> diskBoneNames = new HashMap<>();

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

    /** Bone names of a model's own geometry file, read once and kept. */
    private java.util.Set<String> boneNamesOf(String modelId) {
        return this.diskBoneNames.computeIfAbsent(modelId, id -> {
            YsmModelSource source = this.sources().get(id);

            if (source == null) {
                return java.util.Set.of();
            }

            java.util.Set<String> names = new java.util.LinkedHashSet<>();

            try {
                var geometry = source.readMainModel().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();

                if (geometry.has("bones")) {
                    for (var element : geometry.getAsJsonArray("bones")) {
                        var bone = element.getAsJsonObject();

                        if (bone.has("name")) {
                            names.add(bone.get("name").getAsString());
                        }
                    }
                }
            } catch (Throwable ignored) {
                // A model whose geometry will not parse cannot be told
                // apart this way; the caller treats that as no answer.
            }

            return names;
        });
    }

    /** sanitized id or display name -> model id, for the probe. */
    private Map<String, String> probeCandidates() {
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

        return candidates;
    }

    /** The converted model to draw the given player with, or null for default rendering. */
    @Nullable
    public ConvertedModel modelFor(AbstractClientPlayer player) {
        if (com.argorice.epicysm.client.compat.LookOwners.ownsLook(player)) {
            return null;
        }

        // YSM's own selection (Alt+Y) is the single authority: whatever its
        // renderer shows for this player is what fights. If the texture
        Detection detection = this.probedModels.get(player.getUUID());
        return detection != null ? this.convertedModel(detection, this.foreignTextures.get(player.getUUID())) : null;
    }

    public Map<String, YsmModelSource> sources() {
        if (this.sources == null) {
            this.sources = ModelScanner.scan(Minecraft.getInstance().gameDirectory.toPath());
            EpicYsm.LOGGER.info("Found {} plain YSM model(s)", this.sources.size());
        }

        return Collections.unmodifiableMap(this.sources);
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
        this.diskBoneNames.clear();
        this.sources = null;
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
