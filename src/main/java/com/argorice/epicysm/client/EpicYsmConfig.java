package com.argorice.epicysm.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import javax.annotation.Nullable;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

import com.argorice.epicysm.EpicYsm;
import com.argorice.epicysm.client.ysm.EpicFightItems;
import com.argorice.epicysm.client.ysm.WeaponBones;
import com.argorice.epicysm.client.ysm.YsmSkeletonOverlay;

/**
 * User settings, kept in config/epicysm/config.json and editable from the
 * mod's settings screen (mod list -> EpicYSM -> Config, the settings key,
 * or /epicysm config).
 */
public final class EpicYsmConfig {
    /** What to do in battle mode with a model this mod cannot read. */
    public enum Unreadable {
        KEEP_MODEL,
        KEEP_COMBAT;

        public static Unreadable parse(String name) {
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                return KEEP_MODEL;
            }
        }

        public String key() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    private static boolean loaded;

    private static Unreadable unreadable = Unreadable.KEEP_MODEL;
    private static boolean skeletonOverlay = true;
    private static boolean epicFightItems = true;
    private static boolean hideWeaponsOpen = true;
    private static boolean hideWeaponsLocked = true;
    private static YsmSkeletonOverlay.Hold hold = YsmSkeletonOverlay.Hold.ALL;
    private static boolean bowByYsm = true;
    private static boolean armorOnModels = false;
    private static boolean physics = false;
    private static boolean diagnostics = false;

    private EpicYsmConfig() {
    }

    /* ---------------- getters ---------------- */

    public static Unreadable unreadableModels() {
        ensureLoaded();
        return unreadable;
    }

    /** Epic Fight animations on encrypted models, through Yes Steve Model's own skeleton. */
    public static boolean skeletonOverlay() {
        ensureLoaded();
        return skeletonOverlay;
    }

    /** Held items on encrypted models drawn the way Epic Fight draws them. */
    public static boolean epicFightItems() {
        ensureLoaded();
        return epicFightItems;
    }

    /** Put away the weapons a readable model was built holding while Epic Fight fights. */
    public static boolean hideWeaponsOpen() {
        ensureLoaded();
        return hideWeaponsOpen;
    }

    /** The same for encrypted models. */
    public static boolean hideWeaponsLocked() {
        ensureLoaded();
        return hideWeaponsLocked;
    }

    /** How much of Yes Steve Model's own animation is stopped while Epic Fight poses the body. */
    public static YsmSkeletonOverlay.Hold hold() {
        ensureLoaded();
        return hold;
    }

    /** Drawing a bow with the use key plays Yes Steve Model's own animation. */
    public static boolean bowByYsm() {
        ensureLoaded();
        return bowByYsm;
    }

    /** Vanilla and Epic Fight armor drawn over a readable model. Off: the model is shown as it is. */
    public static boolean armorOnModels() {
        ensureLoaded();
        return armorOnModels;
    }

    /** Secondary motion (hair, skirts) on converted models. */
    public static boolean physics() {
        ensureLoaded();
        return physics;
    }

    /** Verbose log lines and description files for every model. */
    public static boolean diagnostics() {
        ensureLoaded();
        return diagnostics;
    }

    /* ---------------- setters ---------------- */

    public static void setUnreadableModels(Unreadable value) {
        unreadable = value;
        save();
    }

    public static void setSkeletonOverlay(boolean value) {
        skeletonOverlay = value;
        save();
    }

    public static void setEpicFightItems(boolean value) {
        epicFightItems = value;
        apply();
        save();
    }

    public static void setHideWeaponsOpen(boolean value) {
        hideWeaponsOpen = value;
        apply();
        save();
    }

    public static void setHideWeaponsLocked(boolean value) {
        hideWeaponsLocked = value;
        apply();
        save();
    }

    public static void setHold(YsmSkeletonOverlay.Hold value) {
        hold = value;
        apply();
        save();
    }

    public static void setBowByYsm(boolean value) {
        bowByYsm = value;
        save();
    }

    public static void setArmorOnModels(boolean value) {
        armorOnModels = value;
        save();
    }

    public static void setPhysics(boolean value) {
        physics = value;
        save();
    }

    public static void setDiagnostics(boolean value) {
        diagnostics = value;
        save();
    }

    /* ---------------- persistence ---------------- */

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }

        loaded = true;
        JsonObject json = read();

        if (json != null) {
            unreadable = Unreadable.parse(string(json, "unreadable_models", unreadable.key()));
            skeletonOverlay = flag(json, "skeleton_overlay", skeletonOverlay);
            epicFightItems = flag(json, "epic_fight_items", epicFightItems);
            hideWeaponsOpen = flag(json, "hide_model_weapons_open", hideWeaponsOpen);
            hideWeaponsLocked = flag(json, "hide_model_weapons_encrypted", hideWeaponsLocked);
            bowByYsm = flag(json, "bow_by_ysm", bowByYsm);
            armorOnModels = flag(json, "armor_on_models", armorOnModels);
            physics = flag(json, "physics", physics);
            diagnostics = flag(json, "diagnostics", diagnostics);

            try {
                hold = YsmSkeletonOverlay.Hold.valueOf(string(json, "hold", hold.name()).toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {
            }
        }

        apply();
    }

    /** Pushes the settings into the parts of the mod that read them as statics. */
    private static void apply() {
        try {
            EpicFightItems.setOwn(epicFightItems);
            WeaponBones.setHideOpen(hideWeaponsOpen);
            WeaponBones.setHideLocked(hideWeaponsLocked);
            YsmSkeletonOverlay.setHold(hold);
        } catch (Throwable t) {
            EpicYsm.LOGGER.debug("Could not apply settings", t);
        }
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config/epicysm/config.json");
    }

    @Nullable
    private static JsonObject read() {
        Path path = file();

        if (!Files.isRegularFile(path)) {
            return null;
        }

        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            EpicYsm.LOGGER.warn("Failed to read {}", path, e);
            return null;
        }
    }

    private static boolean flag(JsonObject json, String key, boolean fallback) {
        try {
            return json.has(key) ? json.get(key).getAsBoolean() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String string(JsonObject json, String key, String fallback) {
        try {
            return json.has(key) ? json.get(key).getAsString() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void save() {
        Path path = file();
        JsonObject json = new JsonObject();
        json.addProperty("unreadable_models", unreadable.key());
        json.addProperty("skeleton_overlay", skeletonOverlay);
        json.addProperty("epic_fight_items", epicFightItems);
        json.addProperty("hide_model_weapons_open", hideWeaponsOpen);
        json.addProperty("hide_model_weapons_encrypted", hideWeaponsLocked);
        json.addProperty("hold", hold.name().toLowerCase(Locale.ROOT));
        json.addProperty("bow_by_ysm", bowByYsm);
        json.addProperty("armor_on_models", armorOnModels);
        json.addProperty("physics", physics);
        json.addProperty("diagnostics", diagnostics);

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            EpicYsm.LOGGER.warn("Failed to write {}", path, e);
        }
    }
}
