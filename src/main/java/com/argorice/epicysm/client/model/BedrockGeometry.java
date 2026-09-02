package com.argorice.epicysm.client.model;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Parsed form of a Bedrock 1.12.0+ geometry file ("minecraft:geometry"),
 * which is the model format used by Yes Steve Model (via geckolib).
 */
public record BedrockGeometry(float textureWidth, float textureHeight, List<Bone> bones) {
    public record Bone(String name,
                       @Nullable String parent,
                       float[] pivot,
                       float[] rotation,
                       boolean mirror,
                       float inflate,
                       boolean hasInflate,
                       List<Cube> cubes) {
    }

    public record Cube(float[] origin,
                       float[] size,
                       float inflate,
                       boolean hasInflate,
                       @Nullable Boolean mirror,
                       float[] pivot,
                       float[] rotation,
                       @Nullable float[] boxUv,
                       @Nullable Map<String, Face> faces) {
    }

    /** Per-face UV entry; key names are north/south/east/west/up/down. */
    public record Face(float[] uv, float[] uvSize, int uvRotation) {
    }
}
