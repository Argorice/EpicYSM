package com.argorice.epicysm.client.ysm;

import com.mojang.blaze3d.vertex.VertexConsumer;

import javax.annotation.Nullable;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * The buffers a body is drawn into, with every vertex kept besides for its
 * afterimage. Everything is passed on to the real buffers as it comes; the
 * draw on screen is the same as without. A {@code BufferSource} for the
 * same reason the bridge's window is one: a layer that casts the buffers it
 * is handed to flush them is not stopped half-way.
 */
final class AfterimageRecording extends MultiBufferSource.BufferSource {
    private static final com.mojang.blaze3d.vertex.ByteBufferBuilder UNUSED = new com.mojang.blaze3d.vertex.ByteBufferBuilder(256);

    private final MultiBufferSource delegate;
    private final Afterimages.Picture.Builder picture;

    AfterimageRecording(MultiBufferSource delegate, Afterimages.Picture.Builder picture) {
        super(UNUSED, new java.util.LinkedHashMap<>());
        this.delegate = delegate;
        this.picture = picture;
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        VertexConsumer buffer = this.delegate.getBuffer(renderType);
        Afterimages.Part part = this.picture.partFor(renderType);
        return part == null ? buffer : new Kept(buffer, part);
    }

    @Override
    public void endBatch() {
        if (this.delegate instanceof MultiBufferSource.BufferSource source) {
            source.endBatch();
        }
    }

    @Override
    public void endLastBatch() {
        if (this.delegate instanceof MultiBufferSource.BufferSource source) {
            source.endLastBatch();
        }
    }

    @Override
    public void endBatch(RenderType renderType) {
        if (this.delegate instanceof MultiBufferSource.BufferSource source) {
            source.endBatch(renderType);
        }
    }

    /** A buffer that draws, and keeps what it drew. */
    private static final class Kept implements VertexConsumer {
        private final VertexConsumer delegate;
        @Nullable
        private Afterimages.Part part;

        Kept(VertexConsumer delegate, Afterimages.Part part) {
            this.delegate = delegate;
            this.part = part;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.delegate.addVertex(x, y, z);

            if (this.part != null && !this.part.start(x, y, z)) {
                this.part = null;
            }

            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.delegate.setColor(red, green, blue, alpha);

            if (this.part != null) {
                this.part.color(red, green, blue, alpha);
            }

            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.delegate.setUv(u, v);

            if (this.part != null) {
                this.part.uv(u, v);
            }

            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            this.delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            this.delegate.setNormal(x, y, z);

            if (this.part != null) {
                this.part.normal(x, y, z);
            }

            return this;
        }

        // The whole vertex in one call, the way a model is usually drawn:
        // passed on in one call too, so the real buffer keeps its fast way.
        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                              float normalX, float normalY, float normalZ) {
            this.delegate.addVertex(x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);

            if (this.part != null) {
                if (!this.part.start(x, y, z)) {
                    this.part = null;
                    return;
                }

                this.part.color((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, (color >>> 24) & 0xFF);
                this.part.uv(u, v);
                this.part.normal(normalX, normalY, normalZ);
            }
        }
    }
}
