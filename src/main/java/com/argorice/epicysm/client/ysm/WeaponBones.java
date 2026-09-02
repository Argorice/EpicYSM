package com.argorice.epicysm.client.ysm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.client.Minecraft;

import com.argorice.epicysm.EpicYsm;

/**
 * The weapons a model brings with it, as opposed to the one in the player's
 * hand.
 */
public final class WeaponBones {
    private WeaponBones() {
    }

    /**
     * Names that mean "this bone is a weapon", matched whole.
     *
     * Whole, not as a fragment: "bow" has to match a bone called Bow and not
     * the elbow of every model ever made. A trailing number and a trailing
     * "locator" are dropped first, so Sword2 and SwordLocator both land here.
     */
    private static final Set<String> KNOWN = Set.of(
            "weapon", "sword", "blade", "katana", "tachi", "sabre", "saber", "rapier", "greatsword",
            "sheath", "scabbard", "saya", "holster",
            "knife", "dagger", "kunai", "spear", "lance", "halberd", "naginata", "glaive",
            "scythe", "sickle", "axe", "hammer", "mace", "club", "whip", "staff", "wand", "rod",
            "bow", "crossbow", "arrow", "quiver", "bolt",
            "gun", "rifle", "pistol", "revolver", "shotgun", "smg", "sniper", "launcher",
            // A handful of guns that models name after the real thing rather
            // than after what it is. Whole names, so nothing else matches.
            "ak", "ak47", "ak74", "ak12", "m4", "m4a1", "m16", "mp5", "uzi", "glock", "awp", "kar98",
            "scar", "hk416", "famas", "aug", "p90", "deagle",
            "shield", "buckler");

    /** Extra names the player has added, and the file they came from. */
    private static Set<String> extra = Set.of();
    private static boolean loaded;

    /**
     * Whether the model's own weapon is put away, kept separately for the
     * two kinds of model.
     */
    private static boolean hideOpen = true;
    private static boolean hideLocked = true;

    /** For a model whose files this mod can read and convert. */
    public static boolean hideOpen() {
        return hideOpen;
    }

    /** For an encrypted model, drawn by Yes Steve Model itself. */
    public static boolean hideLocked() {
        return hideLocked;
    }

    public static void setHideOpen(boolean value) {
        hideOpen = value;
    }

    public static void setHideLocked(boolean value) {
        hideLocked = value;
    }

    /** Whether this bone is a weapon the model brought with it. */
    public static boolean isWeapon(String boneName) {
        if (boneName == null || boneName.isEmpty()) {
            return false;
        }

        load();
        String name = boneName.toLowerCase(Locale.ROOT);

        if (extra.contains(name)) {
            return true;
        }

        // The one locator that must survive is the hand: that is where Yes
        // Steve Model hangs the item out of the player's own hand, and
        if (isHandLocator(name)) {
            return false;
        }

        // A weapon drawn twice: once solid, once glowing.
        //
        name = withoutGlow(name);

        // The whole name and the name with its trimmings off: a gun called
        // M4A1 has to be read whole, and a bone called Sword2 has not.
        String bare = trim(name);

        if (KNOWN.contains(name) || KNOWN.contains(bare)) {
            return true;
        }

        // Which side of the body a thing is on says nothing about what it
        // is. Models name a weapon twice, once per hand - LeftBow, RightAxe,
        String sideless = withoutSide(bare);

        if (!sideless.equals(bare) && KNOWN.contains(sideless)) {
            return true;
        }

        // And a weapon that leads its own name: ArrowSword, BladeGuard,
        // SpearTip. Only for words long and plain enough that nothing else
        for (String word : LEADING) {
            if (sideless.length() > word.length() && sideless.startsWith(word)) {
                return true;
            }
        }

        // And a weapon that ends its own name: GreenSword, WingSword,
        // DakatiSword, ExtraSword - a model with a dozen swords names each
        for (String word : TRAILING) {
            if (sideless.length() > word.length() && sideless.endsWith(word)) {
                return true;
            }
        }

        return false;
    }

    /** Weapon words a longer name may end with and still be a weapon. */
    private static final Set<String> TRAILING = Set.of(
            "weapon", "sword", "blade", "katana", "sheath", "scabbard", "dagger", "spear", "lance",
            "halberd", "scythe", "sickle", "crossbow", "arrow", "rifle", "pistol", "shotgun",
            "revolver", "shield", "holster", "hammer", "knife", "staff", "wand", "greatsword", "claymore");

    /** Weapon words a longer name may begin with and still be a weapon. */
    private static final Set<String> LEADING = Set.of(
            "weapon", "sword", "blade", "katana", "sheath", "scabbard", "dagger", "spear",
            "halberd", "scythe", "sickle", "crossbow", "arrow", "quiver", "rifle", "pistol", "shotgun",
            "revolver", "shield", "holster");

    /** The name with a leading or trailing side off it, if it had one. */
    private static String withoutGlow(String name) {
        return name.length() > 7 && name.startsWith("ysmglow") ? name.substring(7) : name;
    }

    private static String withoutSide(String name) {
        String at = name;

        for (String side : new String[] { "left", "right" }) {
            if (at.length() > side.length() + 1 && at.startsWith(side)) {
                return at.substring(side.length());
            }
        }

        return at;
    }

    /**
     * Whether this is the bone Yes Steve Model hangs the player's own item
     * on - the hand locator every rig of this shape carries, under one
     * spelling or another.
     */
    public static boolean isHandLocator(String boneName) {
        if (boneName == null || boneName.isEmpty()) {
            return false;
        }

        StringBuilder letters = new StringBuilder();

        for (char c : boneName.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z') {
                letters.append(c);
            }
        }

        String name = letters.toString();
        return name.endsWith("handlocator") || name.equals("itemlocator") || name.equals("handloc");
    }

    /**
     * A bone's name with the parts that do not change what it is removed: a
     * trailing number, a trailing "locator", and the separators around them.
     */
    private static String trim(String name) {
        String at = name;

        for (boolean again = true; again;) {
            again = false;

            while (!at.isEmpty() && (Character.isDigit(at.charAt(at.length() - 1))
                    || at.charAt(at.length() - 1) == '_' || at.charAt(at.length() - 1) == '.'
                    || at.charAt(at.length() - 1) == '-')) {
                at = at.substring(0, at.length() - 1);
                again = true;
            }

            for (String tail : new String[] { "locator", "loc", "bone", "item", "mesh", "model" }) {
                if (at.length() > tail.length() && at.endsWith(tail)) {
                    at = at.substring(0, at.length() - tail.length());
                    again = true;
                }
            }
        }

        return at;
    }

    /** Re-reads the added names, for the settings. */
    public static void reload() {
        loaded = false;
        load();
    }

    private static void load() {
        if (loaded) {
            return;
        }

        loaded = true;
        Set<String> names = new LinkedHashSet<>();

        try {
            Path file = file();

            if (!Files.isRegularFile(file)) {
                Files.createDirectories(file.getParent());
                Files.write(file, List.of(
                        "# One bone name per line: a bone this mod should put away while Epic Fight",
                        "# is posing the model, together with everything hanging off it.",
                        "#",
                        "# Swords, bows, guns and sheaths are already known by name - this file is",
                        "# for the ones named something else. The files in config/epicysm/bones/ list",
                        "# every bone of each model you have worn, so names can be copied from there.",
                        "#",
                        "# Lines starting with # are ignored. `/epicysm reload` re-reads this",
                        "# file without restarting, and the settings screen switches the whole",
                        "# thing off."), StandardCharsets.UTF_8);
                extra = Set.of();
                return;
            }

            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String name = line.trim();

                if (!name.isEmpty() && !name.startsWith("#")) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }

            if (!names.isEmpty()) {
                com.argorice.epicysm.client.Diag.info("Weapons: {} extra bone name(s) to put away, from {}", names.size(), file);
            }
        } catch (Throwable t) {
            EpicYsm.LOGGER.warn("Could not read the list of bones to put away", t);
        }

        extra = names;
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config/epicysm/hidden-bones.txt");
    }

    /**
     * Writes down every bone the model has, so a weapon that is called
     * something this mod does not know can be found and named.
     */
    public static void describe(String model, List<String> bones) {
        try {
            Path file = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config/epicysm/ysm-bones.txt");
            Files.createDirectories(file.getParent());
            List<String> out = new ArrayList<>();
            out.add("Bones of the model Yes Steve Model is showing" + (model == null ? "" : " (" + model + ")"));
            out.add("A name can be copied into hidden-bones.txt to put that bone away in battle.");
            out.add("");
            out.addAll(bones);
            Files.write(file, out, StandardCharsets.UTF_8);
            com.argorice.epicysm.client.Diag.info("Wrote the {} bone name(s) of this model to {}", bones.size(), file);
        } catch (Throwable t) {
            EpicYsm.LOGGER.warn("Could not write the model's bone names", t);
        }
    }
}
