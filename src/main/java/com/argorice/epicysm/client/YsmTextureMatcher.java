package com.argorice.epicysm.client;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import com.argorice.epicysm.EpicYsm;
import com.argorice.epicysm.client.model.YsmModelSource;

/**
 * Identifies which model another mod (YSM) has selected for a player by the
 * pixels of the texture its renderer draws with.
 */
public final class YsmTextureMatcher {
    private static final int MAX_TEXTURE_SIZE = 16384;

    private record TextureRef(String modelId, YsmModelSource source, String path) {
    }

    /** (width<<32|height) -> disk textures of that size. Header-only scan. */
    private Map<Long, List<TextureRef>> byDimensions = null;

    /** Size groups whose members were decoded and hashed already. */
    private final Set<Long> hashedGroups = new HashSet<>();

    /** full pixel hash -> the disk texture it belongs to, filled lazily. */
    private final Map<Long, TextureRef> hashToTexture = new HashMap<>();

    /**
     * Pixel hash -> every model shipping that exact image, for the hashes
     * more than one model ships. These are deliberately kept out of
     * hashToTexture: on their own they cannot say which model is on screen.
     */
    private final Map<Long, List<TextureRef>> sharedHashes = new HashMap<>();

    /**
     * One model a texture could belong to: its id and the file inside it.
     */
    public record Candidate(String modelId, String texturePath) {
    }

    /**
     * Outcome of a match attempt. `readable` false means the texture had no
     * pixels to compare yet (not uploaded), which is worth retrying; with
     * `readable` true an empty modelId is a final "no such model here".
     */
    public record Match(String modelId, String texturePath, boolean readable, List<Candidate> candidates) {
        static final Match UNREADABLE = new Match("", "", false, List.of());

        public Match(String modelId, String texturePath, boolean readable) {
            this(modelId, texturePath, readable, List.of());
        }
    }

    /**
     * Resolves a live foreign texture to a model id. Called only when the
     * foreign texture of a player changes, so a readback per call is fine.
     */
    public Match match(ResourceLocation texture, Map<String, YsmModelSource> sources) {
        AbstractTexture live;

        try {
            live = Minecraft.getInstance().getTextureManager().getTexture(texture);
        } catch (Throwable t) {
            return Match.UNREADABLE;
        }

        if (live == null) {
            return Match.UNREADABLE;
        }

        long hash = 0L;
        long dimensions = 0L;

        // Cheap path first: a texture that kept its NativeImage.
        if (live instanceof DynamicTexture dynamic && dynamic.getPixels() != null) {
            NativeImage pixels = dynamic.getPixels();
            hash = hashImage(pixels);
            dimensions = dimensionKey(pixels.getWidth(), pixels.getHeight());
        }

        if (hash == 0L) {
            long[] result = hashGpuTexture(live);
            hash = result[0];
            dimensions = result[1];
        }

        if (hash == 0L) {
            return Match.UNREADABLE;
        }

        this.ensureDimensionIndex(sources);
        this.hashGroup(dimensions);

        TextureRef ref = this.hashToTexture.get(hash);

        if (ref != null) {
            return new Match(ref.modelId(), ref.path(), true);
        }

        List<TextureRef> shared = this.sharedHashes.get(hash);

        if (shared != null) {
            List<Candidate> candidates = new ArrayList<>();

            for (TextureRef candidate : shared) {
                candidates.add(new Candidate(candidate.modelId(), candidate.path()));
            }

            com.argorice.epicysm.client.Diag.info("Foreign texture {} ({}x{}) is the same image in {} model(s): {}", texture,
                    (int) (dimensions >>> 32), (int) (dimensions & 0xFFFFFFFFL), candidates.size(),
                    candidates.stream().map(Candidate::modelId).distinct().toList());
            return new Match("", "", true, candidates);
        }

        com.argorice.epicysm.client.Diag.info("Foreign texture {} ({}x{}) matches no scanned model texture", texture,
                (int) (dimensions >>> 32), (int) (dimensions & 0xFFFFFFFFL));
        return new Match("", "", true);
    }

    /** Drops all indexes; rebuilt from the given sources on next use. */
    public void reset() {
        this.byDimensions = null;
        this.hashedGroups.clear();
        this.hashToTexture.clear();
        this.sharedHashes.clear();
    }

    /** Header-only pass over every png of every model: no decoding. */
    private void ensureDimensionIndex(Map<String, YsmModelSource> sources) {
        if (this.byDimensions != null) {
            return;
        }

        long start = System.nanoTime();
        this.byDimensions = new HashMap<>();
        int count = 0;
        List<String> withoutTextures = new ArrayList<>();

        for (Map.Entry<String, YsmModelSource> entry : sources.entrySet()) {
            Map<String, Long> textures = entry.getValue().textureDimensions();

            for (Map.Entry<String, Long> texture : textures.entrySet()) {
                this.byDimensions.computeIfAbsent(texture.getValue(), key -> new ArrayList<>())
                        .add(new TextureRef(entry.getKey(), entry.getValue(), texture.getKey()));
                count++;
            }

            if (textures.isEmpty()) {
                withoutTextures.add(entry.getKey());
            }
        }

        com.argorice.epicysm.client.Diag.info("Indexed {} texture(s) of {} model(s) by size in {} ms",
                count, sources.size(), (System.nanoTime() - start) / 1_000_000L);

        // A model with no readable png can never be recognized when YSM
        // selects it: worth naming, since the fix is on the model's side.
        if (!withoutTextures.isEmpty()) {
            EpicYsm.LOGGER.warn("{} model(s) ship no readable texture and cannot be detected: {}",
                    withoutTextures.size(),
                    withoutTextures.size() > 20 ? withoutTextures.subList(0, 20) + " ..." : withoutTextures);
        }
    }

    /** Decodes and hashes one size group, once. */
    private void hashGroup(long dimensions) {
        if (dimensions == 0L || !this.hashedGroups.add(dimensions)) {
            return;
        }

        List<TextureRef> group = this.byDimensions.getOrDefault(dimensions, List.of());
        Map<Long, List<TextureRef>> owners = new HashMap<>();

        for (TextureRef ref : group) {
            byte[] data = ref.source().readEntry(ref.path());

            if (data == null) {
                continue;
            }

            try (NativeImage image = NativeImage.read(new ByteArrayInputStream(data))) {
                long hash = hashImage(image);

                if (hash == 0L) {
                    continue;
                }

                owners.computeIfAbsent(hash, key -> new ArrayList<>()).add(ref);
            } catch (Throwable ignored) {
                // Unreadable png: not a candidate.
            }
        }

        // A picture that several models ship cannot say on its own which
        // model is on screen, and answering with the first one is how a girl
        int shared = 0;

        for (Map.Entry<Long, List<TextureRef>> entry : owners.entrySet()) {
            List<TextureRef> refs = entry.getValue();
            boolean oneModel = refs.stream().map(TextureRef::modelId).distinct().count() == 1L;

            if (oneModel) {
                this.hashToTexture.putIfAbsent(entry.getKey(), refs.get(0));
            } else {
                shared++;
                this.sharedHashes.putIfAbsent(entry.getKey(), List.copyOf(refs));
            }
        }

        if (!group.isEmpty()) {
            com.argorice.epicysm.client.Diag.info("Hashed {} texture(s) of size {}x{}{}", group.size(),
                    (int) (dimensions >>> 32), (int) (dimensions & 0xFFFFFFFFL),
                    shared == 0 ? "" : " (" + shared + " image(s) shipped by several models, kept as candidates)");
        }
    }

    private static long dimensionKey(int width, int height) {
        return ((long) width << 32) | (height & 0xFFFFFFFFL);
    }

    /**
     * Content hash of a live GL texture: level 0 read back as tightly
     * packed RGBA bytes - the same byte sequence hashImage produces for the
     * png on disk. Returns {hash, dimensionKey}.
     */
    private static long[] hashGpuTexture(AbstractTexture texture) {
        int previous = -1;

        try {
            int id = texture.getId();

            if (id <= 0) {
                return new long[] { 0L, 0L };
            }

            previous = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
            GlStateManager._bindTexture(id);

            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

            if (width <= 0 || height <= 0 || width > MAX_TEXTURE_SIZE || height > MAX_TEXTURE_SIZE) {
                return new long[] { 0L, 0L };
            }

            ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);

            try {
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                CRC32 crc = new CRC32();
                crc.update(pixels);
                long hash = ((long) width << 48) ^ ((long) height << 32) ^ crc.getValue();
                return new long[] { hash == 0L ? 1L : hash, dimensionKey(width, height) };
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } catch (Throwable t) {
            return new long[] { 0L, 0L };
        } finally {
            if (previous >= 0) {
                try {
                    GlStateManager._bindTexture(previous);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Content hash of an in-memory image: dimensions folded with a CRC32 of
     * the RGBA byte rows. 0 marks an unhashable image and never matches.
     */
    private static long hashImage(NativeImage image) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();

            if (width <= 0 || height <= 0) {
                return 0L;
            }

            CRC32 crc = new CRC32();
            byte[] row = new byte[width * 4];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = image.getPixelRGBA(x, y);
                    int i = x * 4;
                    row[i] = (byte) (pixel & 0xFF);
                    row[i + 1] = (byte) ((pixel >> 8) & 0xFF);
                    row[i + 2] = (byte) ((pixel >> 16) & 0xFF);
                    row[i + 3] = (byte) ((pixel >>> 24) & 0xFF);
                }

                crc.update(row);
            }

            long hash = ((long) width << 48) ^ ((long) height << 32) ^ crc.getValue();
            return hash == 0L ? 1L : hash;
        } catch (Throwable t) {
            // Non-RGBA formats throw from getPixelRGBA.
            return 0L;
        }
    }
}
