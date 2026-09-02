package com.argorice.epicysm.client.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Parser for Bedrock "minecraft:geometry" model JSON as used by YSM models
 * (models/main.json inside a model folder).
 */
public final class BedrockGeometryParser {
    private BedrockGeometryParser() {
    }

    public static BedrockGeometry parse(JsonObject root) {
        JsonArray geometries = root.getAsJsonArray("minecraft:geometry");

        if (geometries == null || geometries.isEmpty()) {
            throw new IllegalArgumentException("No minecraft:geometry entry in model file");
        }

        JsonObject geometry = geometries.get(0).getAsJsonObject();
        JsonObject description = geometry.getAsJsonObject("description");
        float texWidth = 64.0F;
        float texHeight = 64.0F;

        if (description != null) {
            if (description.has("texture_width")) {
                texWidth = description.get("texture_width").getAsFloat();
            }

            if (description.has("texture_height")) {
                texHeight = description.get("texture_height").getAsFloat();
            }
        }

        List<BedrockGeometry.Bone> bones = new ArrayList<>();
        JsonArray bonesJson = geometry.getAsJsonArray("bones");

        if (bonesJson != null) {
            for (JsonElement boneElement : bonesJson) {
                bones.add(parseBone(boneElement.getAsJsonObject()));
            }
        }

        return new BedrockGeometry(texWidth, texHeight, bones);
    }

    private static BedrockGeometry.Bone parseBone(JsonObject bone) {
        String name = bone.get("name").getAsString();
        String parent = bone.has("parent") ? bone.get("parent").getAsString() : null;
        float[] pivot = floatArray(bone.get("pivot"), 3);
        float[] rotation = floatArray(bone.get("rotation"), 3);
        boolean mirror = bone.has("mirror") && bone.get("mirror").getAsBoolean();
        boolean hasInflate = bone.has("inflate");
        float inflate = hasInflate ? bone.get("inflate").getAsFloat() : 0.0F;
        List<BedrockGeometry.Cube> cubes = new ArrayList<>();

        if (bone.has("cubes")) {
            for (JsonElement cubeElement : bone.getAsJsonArray("cubes")) {
                cubes.add(parseCube(cubeElement.getAsJsonObject()));
            }
        }

        return new BedrockGeometry.Bone(name, parent, pivot, rotation, mirror, inflate, hasInflate, cubes);
    }

    private static BedrockGeometry.Cube parseCube(JsonObject cube) {
        float[] origin = floatArray(cube.get("origin"), 3);
        float[] size = floatArray(cube.get("size"), 3);
        boolean hasInflate = cube.has("inflate");
        float inflate = hasInflate ? cube.get("inflate").getAsFloat() : 0.0F;
        Boolean mirror = cube.has("mirror") ? cube.get("mirror").getAsBoolean() : null;
        float[] pivot = floatArray(cube.get("pivot"), 3);
        float[] rotation = floatArray(cube.get("rotation"), 3);
        float[] boxUv = null;
        Map<String, BedrockGeometry.Face> faces = null;
        JsonElement uv = cube.get("uv");

        if (uv != null) {
            if (uv.isJsonArray()) {
                boxUv = floatArray(uv, 2);
            } else if (uv.isJsonObject()) {
                faces = new LinkedHashMap<>();

                for (Map.Entry<String, JsonElement> entry : uv.getAsJsonObject().entrySet()) {
                    JsonObject face = entry.getValue().getAsJsonObject();
                    float[] faceUv = floatArray(face.get("uv"), 2);
                    float[] faceUvSize = floatArray(face.get("uv_size"), 2);
                    int uvRotation = face.has("uv_rotation") ? face.get("uv_rotation").getAsInt() : 0;
                    faces.put(entry.getKey(), new BedrockGeometry.Face(faceUv, faceUvSize, uvRotation));
                }
            }
        }

        return new BedrockGeometry.Cube(origin, size, inflate, hasInflate, mirror, pivot, rotation, boxUv, faces);
    }

    private static float[] floatArray(JsonElement element, int length) {
        float[] result = new float[length];

        if (element != null && element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();

            for (int i = 0; i < Math.min(length, array.size()); i++) {
                result[i] = array.get(i).getAsFloat();
            }
        }

        return result;
    }
}
