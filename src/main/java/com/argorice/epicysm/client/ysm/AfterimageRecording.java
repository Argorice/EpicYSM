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
    private static final com.mojang.blaze3d.vertex.BufferBuilder UNUSED = new com.mojang.blaze3d.vertex.BufferBuilder(256);

    private final MultiBufferSource delegate;
    private final Afterimages.Picture.Builder picture;

    AfterimageRecording(MultiBufferSource delegate, Afterimages.Picture.Builder picture) {
        super(UNUSED, java.util.Map.of());
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
        @Nullable
        private int[] defaultColor;

        Kept(VertexConsumer delegate, Afterimages.Part part) {
            this.delegate = delegate;
            this.part = part;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            this.delegate.vertex(x, y, z);

            if (this.part != null && !this.part.start((float) x, (float) y, (float) z)) {
                this.part = null;
            } else if (this.part != null && this.defaultColor != null) {
                this.part.color(this.defaultColor[0], this.defaultColor[1], this.defaultColor[2], this.defaultColor[3]);
            }

            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            this.delegate.color(red, green, blue, alpha);

            if (this.part != null) {
                this.part.color(red, green, blue, alpha);
            }

            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            this.delegate.uv(u, v);

            if (this.part != null) {
                this.part.uv(u, v);
            }

            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            this.delegate.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            this.delegate.uv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            this.delegate.normal(x, y, z);

            if (this.part != null) {
                this.part.normal(x, y, z);
            }

            return this;
        }

        @Override
        public void endVertex() {
            this.delegate.endVertex();
        }

        @Override
        public void defaultColor(int red, int green, int blue, int alpha) {
            this.delegate.defaultColor(red, green, blue, alpha);
            this.defaultColor = new int[] { red, green, blue, alpha };
        }

        @Override
        public void unsetDefaultColor() {
            this.delegate.unsetDefaultColor();
            this.defaultColor = null;
        }

        // The whole vertex in one call, the way a model is usually drawn:
        // passed on in one call too, so the real buffer keeps its fast way.
        @Override
        public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v, int overlay,
                           int light, float normalX, float normalY, float normalZ) {
            this.delegate.vertex(x, y, z, red, green, blue, alpha, u, v, overlay, light, normalX, normalY, normalZ);

            if (this.part != null) {
                if (!this.part.start(x, y, z)) {
                    this.part = null;
                    return;
                }

                this.part.color((int) (red * 255.0F), (int) (green * 255.0F), (int) (blue * 255.0F), (int) (alpha * 255.0F));
                this.part.uv(u, v);
                this.part.normal(normalX, normalY, normalZ);
            }
        }
    }
}
