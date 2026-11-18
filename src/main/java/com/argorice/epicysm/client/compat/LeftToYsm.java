package com.argorice.epicysm.client.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import javax.annotation.Nullable;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;

import com.argorice.epicysm.EpicYsm;
import com.argorice.epicysm.client.EpicYsmConfig;

/**
 * The moments a body is left to Yes Steve Model's own animation although
 * Epic Fight is fighting: Epic Fight has no animation for what the player
 * is doing, and Yes Steve Model has one.
 *
 * A bow drawn with the use key and sleep were the first two. A gun of
 * Timeless and Classics Zero or of Superb Warfare in hand is the next:
 * Yes Steve Model plays the model's own hold, aim and reload for those
 * guns, and Epic Fight would stand the model in its idle with the gun
 * held out. And an emote of Emotecraft, which Epic Fight's pose covered.
 *
 * In these moments the player is drawn as out of battle: Epic Fight does
 * not draw, and this mod does not step in between Yes Steve Model and
 * its model. Nothing of those mods is needed at compile time; each is
 * looked up once, by name, and a mod not there costs nothing.
 */
public final class LeftToYsm {
    private LeftToYsm() {
    }

    private static boolean looked;
    @Nullable
    private static MethodHandle taczGunOf;
    @Nullable
    private static Class<?> sbwGun;
    @Nullable
    private static Class<?> emotePlayer;
    @Nullable
    private static MethodHandle playingEmote;
    private static boolean saidGun;
    private static boolean saidEmote;

    /** Whether this body is Yes Steve Model's to animate right now, Epic Fight or no Epic Fight. */
    public static boolean now(LivingEntity entity) {
        if (entity.isSleeping() || drawingABow(entity)) {
            return true;
        }

        if (holdingAGun(entity)) {
            return true;
        }

        return entity instanceof AbstractClientPlayer player && emoting(player);
    }

    /**
     * Whether the player is drawing a bow or loading a crossbow with the use
     * key - the vanilla way, held down.
     */
    public static boolean drawingABow(LivingEntity entity) {
        if (!entity.isUsingItem() || !EpicYsmConfig.bowByYsm()) {
            return false;
        }

        ItemStack using = entity.getUseItem();
        return !using.isEmpty() && using.getItem() instanceof ProjectileWeaponItem;
    }

    /** A gun of Timeless and Classics Zero or Superb Warfare in the main hand. */
    public static boolean holdingAGun(LivingEntity entity) {
        look();

        if ((taczGunOf == null && sbwGun == null) || !EpicYsmConfig.gunsByYsm()) {
            return false;
        }

        ItemStack held = entity.getMainHandItem();

        if (held.isEmpty()) {
            return false;
        }

        boolean gun = false;

        if (sbwGun != null && sbwGun.isInstance(held.getItem())) {
            gun = true;
        } else if (taczGunOf != null) {
            try {
                gun = taczGunOf.invoke(held) != null;
            } catch (Throwable t) {
                gun = false;
            }
        }

        if (gun && !saidGun) {
            saidGun = true;
            EpicYsm.LOGGER.info("Compatibility: a gun of Timeless and Classics Zero or Superb Warfare is in hand; while it is,"
                    + " the body is Yes Steve Model's to animate, in battle too - Yes Steve Model plays the model's own hold,"
                    + " aim and reload, and Epic Fight has no animation for a gun. The settings screen gives it to Epic Fight");
        }

        return gun;
    }

    /** An emote of Emotecraft playing on this player. */
    public static boolean emoting(AbstractClientPlayer player) {
        look();

        if (playingEmote == null || !EpicYsmConfig.emotesByYsm() || emotePlayer == null || !emotePlayer.isInstance(player)) {
            return false;
        }

        boolean emote;

        try {
            emote = (boolean) playingEmote.invoke(player);
        } catch (Throwable t) {
            return false;
        }

        if (emote && !saidEmote) {
            saidEmote = true;
            EpicYsm.LOGGER.info("Compatibility: an emote of Emotecraft is playing; while it plays, the body is Yes Steve Model's,"
                    + " in battle too - Epic Fight's pose covered the emote. The settings screen gives it to Epic Fight");
        }

        return emote;
    }

    private static void look() {
        if (looked) {
            return;
        }

        looked = true;
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();

        try {
            Class<?> gun = Class.forName("com.tacz.guns.api.item.IGun", false, LeftToYsm.class.getClassLoader());
            taczGunOf = lookup.findStatic(gun, "getIGunOrNull", MethodType.methodType(gun, ItemStack.class));
            EpicYsm.LOGGER.info("Compatibility: Timeless and Classics Zero is here; a gun of it in hand leaves the body to"
                    + " Yes Steve Model's own animation");
        } catch (Throwable t) {
            taczGunOf = null;
        }

        for (String name : new String[] { "com.atsuishio.superbwarfare.item.gun.GunItem", "net.mcreator.superbwarfare.item.gun.GunItem" }) {
            try {
                sbwGun = Class.forName(name, false, LeftToYsm.class.getClassLoader());
                EpicYsm.LOGGER.info("Compatibility: Superb Warfare is here; a gun of it in hand leaves the body to Yes Steve"
                        + " Model's own animation");
                break;
            } catch (Throwable t) {
                sbwGun = null;
            }
        }

        try {
            emotePlayer = Class.forName("io.github.kosmx.emotes.executor.emotePlayer.IEmotePlayerEntity", false,
                    LeftToYsm.class.getClassLoader());
            playingEmote = lookup.findVirtual(emotePlayer, "isPlayingEmote", MethodType.methodType(boolean.class));
            EpicYsm.LOGGER.info("Compatibility: Emotecraft is here; an emote leaves the body to Yes Steve Model's own animation");
        } catch (Throwable t) {
            emotePlayer = null;
            playingEmote = null;
        }
    }
}
