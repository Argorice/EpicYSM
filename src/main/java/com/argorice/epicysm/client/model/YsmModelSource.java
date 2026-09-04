package com.argorice.epicysm.client.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A discovered YSM model in plain (unencrypted) form: either a directory or
 * a plain zip archive containing ysm.json, models/main.json and textures.
 *
 * Encrypted .ysm packages are intentionally not supported: they exist to
 * protect model authors, and this mod respects that.
 */
public final class YsmModelSource {
    private final String id;
    private final Path path;
    private final boolean zip;

    private YsmModelSource(String id, Path path, boolean zip) {
        this.id = id;
        this.path = path;
        this.zip = zip;
    }

    /**
     * Geometry locations a model may use: the spec-2 layout
     * (models/main.json, possibly redirected by ysm.json) and the older
     * flat layout, where main.json and its textures sit at the root. Both
     * are common in the wild, so both are accepted.
     */
    private static final String[] MAIN_MODEL_CANDIDATES = { "models/main.json", "main.json" };

    @Nullable
    public static YsmModelSource of(Path path) {
        String fileName = path.getFileName().toString();

        if (Files.isDirectory(path)) {
            if (Files.isRegularFile(path.resolve("ysm.json"))) {
                return new YsmModelSource(sanitize(fileName), path, false);
            }

            for (String candidate : MAIN_MODEL_CANDIDATES) {
                if (Files.isRegularFile(path.resolve(candidate))) {
                    return new YsmModelSource(sanitize(fileName), path, false);
                }
            }
        } else if (Files.isRegularFile(path) && fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            try (ZipFile zipFile = new ZipFile(path.toFile())) {
                boolean isModel = zipFile.getEntry("ysm.json") != null;

                for (String candidate : MAIN_MODEL_CANDIDATES) {
                    isModel |= zipFile.getEntry(candidate) != null;
                }

                if (isModel) {
                    String baseName = fileName.substring(0, fileName.length() - 4);
                    return new YsmModelSource(sanitize(baseName), path, true);
                }
            } catch (IOException e) {
                return null;
            }
        }

        return null;
    }

    /** Stable identifier used in commands and the config file. */
    public String id() {
        return this.id;
    }

    public Path path() {
        return this.path;
    }

    /** Parsed ysm.json, or null if the model has none. */
    @Nullable
    public JsonObject readMetadata() {
        return readJson("ysm.json");
    }

    /** Optional per-model tweaks for this mod (bone mapping, hidden bones). */
    @Nullable
    public JsonObject readOverrides() {
        return readJson("epicysm.json");
    }

    public JsonObject readMainModel() throws IOException {
        JsonObject metadata = readMetadata();
        String declaredPath = null;

        if (metadata != null && metadata.has("files")) {
            JsonObject files = metadata.getAsJsonObject("files");

            if (files.has("player")) {
                JsonObject player = files.getAsJsonObject("player");

                if (player.has("model")) {
                    com.google.gson.JsonElement model = player.get("model");

                    if (model.isJsonObject() && model.getAsJsonObject().has("main")) {
                        declaredPath = model.getAsJsonObject().get("main").getAsString();
                    } else if (model.isJsonPrimitive()) {
                        declaredPath = model.getAsString();
                    }
                }
            }
        }

        byte[] data = declaredPath != null ? readBytes(declaredPath) : null;

        for (int i = 0; data == null && i < MAIN_MODEL_CANDIDATES.length; i++) {
            data = readBytes(MAIN_MODEL_CANDIDATES[i]);
        }

        if (data == null) {
            throw new IOException("Model " + this.id + " has no readable main geometry");
        }

        return JsonParser.parseString(new String(data, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /**
     * Reads the main texture of the model: properties.default_texture from
     * ysm.json when present, then the texture ysm.json lists, then the
     * first plausible png - under textures/ for the spec-2 layout, or at
     * the root for the older flat one.
     */
    @Nullable
    public byte[] readMainTexture() {
        JsonObject metadata = readMetadata();

        if (metadata != null && metadata.has("properties")) {
            JsonObject properties = metadata.getAsJsonObject("properties");

            if (properties.has("default_texture")) {
                String name = properties.get("default_texture").getAsString();
                byte[] data = readBytes("textures/" + name + ".png");

                if (data == null) {
                    data = readBytes("textures/" + name);
                }

                if (data != null) {
                    return data;
                }
            }
        }

        for (String declared : listDeclaredTexturePaths()) {
            byte[] data = readBytes(declared);

            if (data != null) {
                return data;
            }
        }

        // Flat layout: main texture next to main.json.
        for (String candidate : new String[] { "texture.png", "main.png", "skin.png" }) {
            byte[] data = readBytes(candidate);

            if (data != null) {
                return data;
            }
        }

        // Nothing named conventionally: take the png whose size matches the
        // texture size the geometry was drawn against. Works whatever the
        byte[] matched = readTextureMatchingGeometry();

        if (matched != null) {
            return matched;
        }

        // Fallback: first png under textures/ that is not an auxiliary texture.
        try {
            if (this.zip) {
                try (ZipFile zipFile = new ZipFile(this.path.toFile())) {
                    return zipFile.stream()
                            .filter(entry -> isMainTextureCandidate(entry.getName()))
                            .findFirst()
                            .map(entry -> {
                                try (InputStream in = zipFile.getInputStream(entry)) {
                                    return in.readAllBytes();
                                } catch (IOException e) {
                                    return null;
                                }
                            })
                            .orElse(null);
                }
            } else {
                Path textures = this.path.resolve("textures");

                if (Files.isDirectory(textures)) {
                    try (var stream = Files.list(textures)) {
                        Path candidate = stream
                                .filter(p -> isMainTextureCandidate("textures/" + p.getFileName()))
                                .findFirst()
                                .orElse(null);

                        if (candidate != null) {
                            return Files.readAllBytes(candidate);
                        }
                    }
                }
            }
        } catch (IOException e) {
            return null;
        }

        return null;
    }

    /**
     * Every parsed animation file of the model: everything under
     * animations/ plus any files listed in ysm.json files.player.animation.
     * Used to find bones the model keeps hidden by default.
     */
    public java.util.List<JsonObject> readAnimationFiles() {
        java.util.Map<String, JsonObject> out = new java.util.LinkedHashMap<>();

        try {
            if (this.zip) {
                try (ZipFile zipFile = new ZipFile(this.path.toFile())) {
                    var entries = zipFile.entries();

                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String lower = entry.getName().toLowerCase(Locale.ROOT);

                        if (!entry.isDirectory() && isAnimationEntry(lower)) {
                            JsonObject parsed = readJson(entry.getName());

                            if (parsed != null) {
                                out.put(entry.getName(), parsed);
                            }
                        }
                    }
                }
            } else {
                try (var stream = Files.walk(this.path, 3)) {
                    for (Path file : (Iterable<Path>) stream::iterator) {
                        if (!Files.isRegularFile(file)) {
                            continue;
                        }

                        String relative = this.path.relativize(file).toString().replace('\\', '/');

                        if (isAnimationEntry(relative.toLowerCase(Locale.ROOT))) {
                            JsonObject parsed = readJson(relative);

                            if (parsed != null) {
                                out.put(relative, parsed);
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // Whatever was read so far is still usable.
        }

        JsonObject metadata = readMetadata();

        if (metadata != null && metadata.has("files") && metadata.getAsJsonObject("files").has("player")) {
            JsonObject player = metadata.getAsJsonObject("files").getAsJsonObject("player");

            if (player.has("animation")) {
                // Either a list of paths or a {"key": "path"} object.
                java.util.List<com.google.gson.JsonElement> items = new java.util.ArrayList<>();
                com.google.gson.JsonElement animation = player.get("animation");

                if (animation.isJsonArray()) {
                    animation.getAsJsonArray().forEach(items::add);
                } else if (animation.isJsonObject()) {
                    animation.getAsJsonObject().entrySet().forEach(entry -> items.add(entry.getValue()));
                }

                for (com.google.gson.JsonElement item : items) {
                    try {
                        String entryPath = item.getAsString();

                        if (!out.containsKey(entryPath)) {
                            JsonObject parsed = readJson(entryPath);

                            if (parsed != null) {
                                out.put(entryPath, parsed);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        return new java.util.ArrayList<>(out.values());
    }

    /**
     * The png whose pixel size equals the texture size declared by the
     * geometry, preferring files that do not look like auxiliary maps.
     */
    @Nullable
    private byte[] readTextureMatchingGeometry() {
        int wantedWidth;
        int wantedHeight;

        try {
            JsonObject geometry = readMainModel();
            JsonObject description = geometry.getAsJsonArray("minecraft:geometry").get(0)
                    .getAsJsonObject().getAsJsonObject("description");
            wantedWidth = description.get("texture_width").getAsInt();
            wantedHeight = description.get("texture_height").getAsInt();
        } catch (Exception e) {
            return null;
        }

        if (wantedWidth <= 0 || wantedHeight <= 0) {
            return null;
        }

        String best = null;
        String bestAuxiliary = null;
        int bestScale = Integer.MAX_VALUE;
        int bestAuxiliaryScale = Integer.MAX_VALUE;

        for (Map.Entry<String, Long> texture : textureDimensions().entrySet()) {
            int width = (int) (texture.getValue() >>> 32);
            int height = (int) (texture.getValue() & 0xFFFFFFFFL);

            // Authors often ship an upscaled texture without touching the
            // declared size; UVs are relative, so any whole multiple fits.
            if (width % wantedWidth != 0 || height % wantedHeight != 0
                    || width / wantedWidth != height / wantedHeight) {
                continue;
            }

            int scale = width / wantedWidth;

            if (isAuxiliaryTextureName(texture.getKey().toLowerCase(Locale.ROOT))) {
                if (scale < bestAuxiliaryScale) {
                    bestAuxiliaryScale = scale;
                    bestAuxiliary = texture.getKey();
                }
            } else if (scale < bestScale) {
                bestScale = scale;
                best = texture.getKey();
            }
        }

        String chosen = best != null ? best : bestAuxiliary;
        return chosen != null ? readBytes(chosen) : null;
    }

    private static boolean isAuxiliaryTextureName(String lowerPath) {
        String baseName = lowerPath.substring(lowerPath.lastIndexOf('/') + 1);

        return lowerPath.startsWith("avatar/") || lowerPath.contains("/pbr/")
                || baseName.startsWith("arrow") || baseName.startsWith("wing")
                || baseName.contains("normal") || baseName.contains("emissive")
                || baseName.contains("_s.") || baseName.contains("_n.")
                || baseName.startsWith("gui") || baseName.startsWith("background");
    }

    /** Width/height from a png IHDR header, packed, or 0 if not a png. */
    private static long pngDimensionKey(byte[] data) {
        if (data.length < 24 || data[0] != (byte) 0x89 || data[1] != 'P' || data[2] != 'N' || data[3] != 'G') {
            return 0L;
        }

        long width = ((data[16] & 0xFFL) << 24) | ((data[17] & 0xFFL) << 16) | ((data[18] & 0xFFL) << 8) | (data[19] & 0xFFL);
        long height = ((data[20] & 0xFFL) << 24) | ((data[21] & 0xFFL) << 16) | ((data[22] & 0xFFL) << 8) | (data[23] & 0xFFL);
        return (width << 32) | height;
    }

    /**
     * Animation files live under animations/ in the spec-2 layout and as
     * *.animation.json at the root in the older flat one.
     */
    private static boolean isAnimationEntry(String lowerPath) {
        return lowerPath.endsWith(".json")
                && (lowerPath.startsWith("animations/") || lowerPath.endsWith(".animation.json"));
    }

    /** Texture paths declared in ysm.json, in order. */
    private java.util.List<String> listDeclaredTexturePaths() {
        java.util.List<String> out = new java.util.ArrayList<>();
        JsonObject metadata = readMetadata();

        if (metadata == null || !metadata.has("files") || !metadata.getAsJsonObject("files").has("player")) {
            return out;
        }

        JsonObject player = metadata.getAsJsonObject("files").getAsJsonObject("player");

        if (!player.has("texture")) {
            return out;
        }

        com.google.gson.JsonElement texture = player.get("texture");
        java.util.List<com.google.gson.JsonElement> items = new java.util.ArrayList<>();

        if (texture.isJsonArray()) {
            texture.getAsJsonArray().forEach(items::add);
        } else {
            items.add(texture);
        }

        for (com.google.gson.JsonElement item : items) {
            try {
                if (item.isJsonPrimitive()) {
                    out.add(item.getAsString());
                } else if (item.isJsonObject() && item.getAsJsonObject().has("uv")) {
                    out.add(item.getAsJsonObject().get("uv").getAsString());
                }
            } catch (Exception ignored) {
            }
        }

        return out;
    }

    /**
     * Every parsed animation-controller file of the model (controller/ or
     * controllers/ folder). Controllers decide which animations play in the
     * default state, which in turn decide what geometry is visible.
     */
    public java.util.List<JsonObject> readControllerFiles() {
        java.util.List<JsonObject> out = new java.util.ArrayList<>();

        try {
            if (this.zip) {
                try (ZipFile zipFile = new ZipFile(this.path.toFile())) {
                    var entries = zipFile.entries();

                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String lower = entry.getName().toLowerCase(Locale.ROOT);

                        if (!entry.isDirectory() && (lower.startsWith("controller/") || lower.startsWith("controllers/")) && lower.endsWith(".json")) {
                            JsonObject parsed = readJson(entry.getName());

                            if (parsed != null) {
                                out.add(parsed);
                            }
                        }
                    }
                }
            } else {
                for (String folder : new String[] { "controller", "controllers" }) {
                    Path controllers = this.path.resolve(folder);

                    if (Files.isDirectory(controllers)) {
                        try (var stream = Files.walk(controllers)) {
                            for (Path file : (Iterable<Path>) stream::iterator) {
                                if (Files.isRegularFile(file) && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
                                    JsonObject parsed = readJson(this.path.relativize(file).toString().replace('\\', '/'));

                                    if (parsed != null) {
                                        out.add(parsed);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // Whatever was read so far is still usable.
        }

        return out;
    }

    /**
     * Every png under textures/ (variants and auxiliary ones included),
     * keyed by its path inside the model. Used to identify which model a
     * live texture registered by YSM itself belongs to.
     */
    public java.util.Map<String, byte[]> readAllTextures() {
        java.util.Map<String, byte[]> out = new java.util.LinkedHashMap<>();

        try {
            if (this.zip) {
                try (ZipFile zipFile = new ZipFile(this.path.toFile())) {
                    var entries = zipFile.entries();

                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String lower = entry.getName().toLowerCase(Locale.ROOT);

                        if (!entry.isDirectory() && lower.startsWith("textures/") && lower.endsWith(".png")) {
                            try (InputStream in = zipFile.getInputStream(entry)) {
                                out.put(entry.getName(), in.readAllBytes());
                            }
                        }
                    }
                }
            } else {
                Path textures = this.path.resolve("textures");

                if (Files.isDirectory(textures)) {
                    try (var stream = Files.walk(textures)) {
                        for (Path file : (Iterable<Path>) stream::iterator) {
                            if (Files.isRegularFile(file) && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
                                out.put(this.path.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // Whatever was read so far is still usable.
        }

        return out;
    }

    private static boolean isMainTextureCandidate(String entryName) {
        String lower = entryName.toLowerCase(Locale.ROOT);

        if (!lower.startsWith("textures/") || !lower.endsWith(".png")) {
            return false;
        }

        String baseName = lower.substring("textures/".length());
        return !baseName.contains("/") && !baseName.startsWith("arrow") && !baseName.startsWith("wing");
    }

    /** The ysm.json display name, sanitized like the model id, or "". */
    public String metadataNameSanitized() {
        JsonObject metadata = readMetadata();

        if (metadata != null && metadata.has("metadata")) {
            JsonObject inner = metadata.getAsJsonObject("metadata");

            if (inner.has("name")) {
                return sanitize(inner.get("name").getAsString());
            }
        }

        return "";
    }

    @Nullable
    private JsonObject readJson(String entryPath) {
        byte[] data = readBytes(entryPath);

        if (data == null) {
            return null;
        }

        try {
            return JsonParser.parseString(new String(data, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /** Raw bytes of an entry inside the model (folder file or zip entry). */
    @Nullable
    public byte[] readEntry(String entryPath) {
        return readBytes(entryPath);
    }

    /**
     * Path and pixel size of every png in the model, read from the png
     * headers in a single pass over the archive. Sizes are packed as
     * (width &lt;&lt; 32 | height); entries that are not pngs are skipped.
     */
    /** Pictures that are not the model. */
    private static boolean isNotAModelTexture(String path) {
        String lower = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        String name = lower.substring(lower.lastIndexOf('/') + 1);

        for (String folder : new String[] { "avatar/", "avatars/", "preview/", "previews/", "icon/", "screenshot/" }) {
            if (lower.startsWith(folder) || lower.contains("/" + folder)) {
                return true;
            }
        }

        return name.startsWith("ysm-pack") || name.equals("pack.png") || name.equals("icon.png")
                || name.equals("preview.png") || name.equals("logo.png");
    }

    public java.util.Map<String, Long> textureDimensions() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();

        try {
            if (this.zip) {
                try (ZipFile zipFile = new ZipFile(this.path.toFile())) {
                    var entries = zipFile.entries();

                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();

                        if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".png")
                                || isNotAModelTexture(entry.getName())) {
                            continue;
                        }

                        try (InputStream in = zipFile.getInputStream(entry)) {
                            long dimensions = pngDimensionKey(in.readNBytes(24));

                            if (dimensions != 0L) {
                                out.put(entry.getName(), dimensions);
                            }
                        } catch (IOException ignored) {
                        }
                    }
                }
            } else {
                try (var stream = Files.walk(this.path, 4)) {
                    for (Path file : (Iterable<Path>) stream::iterator) {
                        String relative = this.path.relativize(file).toString().replace('\\', '/');

                        if (!Files.isRegularFile(file) || !relative.toLowerCase(Locale.ROOT).endsWith(".png")
                                || isNotAModelTexture(relative)) {
                            continue;
                        }

                        try (InputStream in = Files.newInputStream(file)) {
                            long dimensions = pngDimensionKey(in.readNBytes(24));

                            if (dimensions != 0L) {
                                out.put(this.path.relativize(file).toString().replace('\\', '/'), dimensions);
                            }
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // Whatever was read so far is still usable.
        }

        return out;
    }

    /**
     * Paths of every png anywhere in the model (any folder - some authors
     * keep skins outside textures/), plus the textures ysm.json lists. Used
     * to identify which model a texture registered by YSM belongs to.
     */
    public java.util.List<String> listTexturePaths() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();

        try {
            if (this.zip) {
                try (ZipFile zipFile = new ZipFile(this.path.toFile())) {
                    var entries = zipFile.entries();

                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();

                        if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                            out.add(entry.getName());
                        }
                    }
                }
            } else {
                try (var stream = Files.walk(this.path, 4)) {
                    for (Path file : (Iterable<Path>) stream::iterator) {
                        if (Files.isRegularFile(file) && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
                            out.add(this.path.relativize(file).toString().replace('\\', '/'));
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // Whatever was listed so far is still usable.
        }

        JsonObject metadata = readMetadata();

        if (metadata != null && metadata.has("files") && metadata.getAsJsonObject("files").has("player")) {
            JsonObject player = metadata.getAsJsonObject("files").getAsJsonObject("player");

            if (player.has("texture")) {
                com.google.gson.JsonElement texture = player.get("texture");
                java.util.List<com.google.gson.JsonElement> items = new java.util.ArrayList<>();

                if (texture.isJsonArray()) {
                    texture.getAsJsonArray().forEach(items::add);
                } else {
                    items.add(texture);
                }

                for (com.google.gson.JsonElement item : items) {
                    try {
                        if (item.isJsonPrimitive()) {
                            out.add(item.getAsString());
                        } else if (item.isJsonObject() && item.getAsJsonObject().has("uv")) {
                            out.add(item.getAsJsonObject().get("uv").getAsString());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        return new java.util.ArrayList<>(out);
    }

    /**
     * An entry name from a model's own json is only ever a relative path
     * inside that model: no drive letters, no leading slash, no "..".
     */
    static boolean insideModel(@Nullable String entryPath) {
        if (entryPath == null || entryPath.isEmpty() || entryPath.length() > 512) {
            return false;
        }

        if (entryPath.startsWith("/") || entryPath.startsWith("\\") || entryPath.indexOf(':') >= 0
                || entryPath.indexOf('\0') >= 0) {
            return false;
        }

        for (String part : entryPath.split("[/\\\\]")) {
            if (part.equals("..")) {
                return false;
            }
        }

        return true;
    }

    @Nullable
    private byte[] readBytes(String entryPath) {
        if (!insideModel(entryPath)) {
            return null;
        }

        try {
            if (this.zip) {
                try (ZipFile zipFile = new ZipFile(this.path.toFile())) {
                    ZipEntry entry = zipFile.getEntry(entryPath);

                    if (entry == null) {
                        return null;
                    }

                    try (InputStream in = zipFile.getInputStream(entry)) {
                        return in.readAllBytes();
                    }
                }
            } else {
                Path root = this.path.toAbsolutePath().normalize();
                Path file = root.resolve(entryPath).normalize();

                if (!file.startsWith(root)) {
                    return null;
                }

                return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Normalizes an arbitrary name the same way model ids are built. */
    public static String sanitize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }
}
