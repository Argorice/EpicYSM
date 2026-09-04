package com.argorice.epicysm.client.ysm;

import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;

import net.minecraft.resources.ResourceLocation;

import com.argorice.epicysm.EpicYsm;

/** Measures how large Yes Steve Model actually draws a model. */
public final class YsmScaleProbe {
    private static final YsmScaleProbe INSTANCE = new YsmScaleProbe();

    public static YsmScaleProbe get() {
        return INSTANCE;
    }

    /** Frames spent measuring one model before giving up on it. */
    private static final int ATTEMPTS = 6;

    /** A scale outside this range is not a scale, it is a mistake. */
    private static final float MIN_SCALE = 0.05F;
    private static final float MAX_SCALE = 8.0F;

    private final Map<ResourceLocation, float[]> measured = new HashMap<>();
    private final Map<ResourceLocation, Integer> attempts = new HashMap<>();

    private ResourceLocation subject;
    private Matrix4f before;

    private YsmScaleProbe() {
    }

    /** The scale YSM draws this model at, as {width, height}, or null. */
    public float[] scaleFor(ResourceLocation texture) {
        return texture == null ? null : this.measured.get(texture);
    }

    /** Whether this model still needs measuring. */
    public boolean wants(ResourceLocation texture) {
        return texture != null && !this.measured.containsKey(texture)
                && this.attempts.getOrDefault(texture, 0) < ATTEMPTS;
    }

    /** Called as YSM's render begins, before it has scaled anything. */
    public void begin(ResourceLocation texture, Matrix4f pose) {
        this.subject = texture;
        this.before = new Matrix4f(pose);
        this.attempts.merge(texture, 1, Integer::sum);
    }

    /** Called from inside that render, once YSM has scaled the model. */
    public void finish(Matrix4f pose) {
        if (this.subject == null || this.before == null) {
            return;
        }

        ResourceLocation texture = this.subject;
        this.subject = null;

        try {
            Matrix4f delta = this.before.invert(new Matrix4f()).mul(pose);
            float width = 0.5F * (length(delta, 0) + length(delta, 2));
            float height = length(delta, 1);

            if (!Float.isFinite(width) || !Float.isFinite(height)
                    || width < MIN_SCALE || width > MAX_SCALE
                    || height < MIN_SCALE || height > MAX_SCALE) {
                return;
            }

            this.measured.put(texture, new float[] { width, height });
            com.argorice.epicysm.client.Diag.info("Yes Steve Model draws {} at {}x wide and {}x tall; the converted model will be"
                    + " built at the same size", texture, round(width), round(height));
        } catch (Throwable ignored) {
            // A pose stack that cannot be inverted tells us nothing.
        }
    }

    /** Throws away a measurement that turned out to be of the wrong thing. */
    public void discard(ResourceLocation texture) {
        if (texture != null) {
            this.measured.remove(texture);
        }
    }

    public void reset() {
        this.measured.clear();
        this.attempts.clear();
        this.subject = null;
        this.before = null;
    }

    private static float length(Matrix4f matrix, int column) {
        return switch (column) {
            case 0 -> (float) Math.sqrt(matrix.m00() * matrix.m00() + matrix.m01() * matrix.m01() + matrix.m02() * matrix.m02());
            case 1 -> (float) Math.sqrt(matrix.m10() * matrix.m10() + matrix.m11() * matrix.m11() + matrix.m12() * matrix.m12());
            default -> (float) Math.sqrt(matrix.m20() * matrix.m20() + matrix.m21() * matrix.m21() + matrix.m22() * matrix.m22());
        };
    }

    private static float round(float value) {
        return Math.round(value * 1000.0F) / 1000.0F;
    }
}
