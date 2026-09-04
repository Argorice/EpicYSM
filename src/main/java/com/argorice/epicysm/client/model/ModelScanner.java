package com.argorice.epicysm.client.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.argorice.epicysm.EpicYsm;

/** Scans known locations for plain YSM models: */
public final class ModelScanner {
    private ModelScanner() {
    }

    public static Map<String, YsmModelSource> scan(Path gameDirectory) {
        Map<String, YsmModelSource> result = new LinkedHashMap<>();
        Path configDir = gameDirectory.resolve("config");

        scanDirectory(configDir.resolve("epicysm/models"), result, 4);
        scanDirectory(configDir.resolve("yes_steve_model/custom"), result, 4);
        scanDirectory(configDir.resolve("yes_steve_model/builtin"), result, 4);
        scanDirectory(configDir.resolve("yes_steve_model/auth"), result, 4);

        return result;
    }

    /**
     * Folder names that are parts of a model, never a model of their own.
     * Without this a stray models/ folder whose parent is not recognized
     * would be registered as a model called "models".
     */
    private static final java.util.Set<String> MODEL_INTERNAL_FOLDERS = java.util.Set.of(
            "models", "textures", "animations", "animation", "controller", "controllers",
            "avatar", "sounds", "lang", "functions", "pbr", "assets");

    /**
     * Registers every model in the directory. A subdirectory that is not a
     * model itself is treated as a group folder (YSM's builtin library and
     * shared packs nest models several levels deep) and scanned deeper.
     */
    private static void scanDirectory(Path directory, Map<String, YsmModelSource> result, int depth) {
        if (!Files.isDirectory(directory)) {
            return;
        }

        try (var stream = Files.list(directory)) {
            stream.sorted().forEach(path -> {
                if (Files.isDirectory(path)
                        && MODEL_INTERNAL_FOLDERS.contains(path.getFileName().toString().toLowerCase(java.util.Locale.ROOT))) {
                    return;
                }

                YsmModelSource source = YsmModelSource.of(path);

                if (source != null) {
                    if (!result.containsKey(source.id())) {
                        result.put(source.id(), source);
                    }
                } else if (depth > 1 && Files.isDirectory(path)) {
                    scanDirectory(path, result, depth - 1);
                }
            });
        } catch (IOException e) {
            EpicYsm.LOGGER.warn("Failed to scan model directory {}", directory, e);
        }
    }
}
