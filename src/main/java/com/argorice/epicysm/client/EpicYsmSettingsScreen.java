package com.argorice.epicysm.client;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import com.argorice.epicysm.client.ysm.YsmSkeletonOverlay;

/** The mod's settings, as a scrollable vanilla-style options screen. */
public final class EpicYsmSettingsScreen extends OptionsSubScreen {
    public EpicYsmSettingsScreen(@Nullable Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("epicysm.settings.title"));
    }

    @Override
    protected void addOptions() {
        if (this.list == null) {
            return;
        }

        this.list.addBig(onOff("overlay", EpicYsmConfig.skeletonOverlay(), EpicYsmConfig::setSkeletonOverlay));
        this.list.addBig(choice("items", EpicYsmConfig.epicFightItems(), EpicYsmConfig::setEpicFightItems, Codec.BOOL,
                v -> Component.translatable(v ? "epicysm.settings.items.epicfight" : "epicysm.settings.items.ysm"),
                Boolean.TRUE, Boolean.FALSE));
        this.list.addBig(choice("bow", EpicYsmConfig.bowByYsm(), EpicYsmConfig::setBowByYsm, Codec.BOOL,
                v -> Component.translatable(v ? "epicysm.settings.bow.ysm" : "epicysm.settings.bow.epicfight"),
                Boolean.TRUE, Boolean.FALSE));
        this.list.addBig(onOff("armor", EpicYsmConfig.armorOnModels(), EpicYsmConfig::setArmorOnModels));
        this.list.addBig(onOff("hide_locked", EpicYsmConfig.hideWeaponsLocked(), EpicYsmConfig::setHideWeaponsLocked));
        this.list.addBig(onOff("hide_open", EpicYsmConfig.hideWeaponsOpen(), EpicYsmConfig::setHideWeaponsOpen));
        this.list.addBig(choice("hold", EpicYsmConfig.hold(), EpicYsmConfig::setHold,
                Codec.STRING.xmap(YsmSkeletonOverlay.Hold::valueOf, Enum::name),
                v -> Component.translatable("epicysm.settings.hold." + v.name().toLowerCase(Locale.ROOT)),
                YsmSkeletonOverlay.Hold.values()));
        this.list.addBig(choice("unreadable", EpicYsmConfig.unreadableModels(), EpicYsmConfig::setUnreadableModels,
                Codec.STRING.xmap(EpicYsmConfig.Unreadable::parse, EpicYsmConfig.Unreadable::key),
                v -> Component.translatable("epicysm.settings.unreadable." + v.key()),
                EpicYsmConfig.Unreadable.values()));
        this.list.addBig(onOff("physics", EpicYsmConfig.physics(), EpicYsmConfig::setPhysics));
        this.list.addBig(onOff("diagnostics", EpicYsmConfig.diagnostics(), EpicYsmConfig::setDiagnostics));
    }

    private static OptionInstance<Boolean> onOff(String key, boolean value, Consumer<Boolean> setter) {
        return OptionInstance.createBoolean("epicysm.settings." + key,
                OptionInstance.cachedConstantTooltip(Component.translatable("epicysm.settings." + key + ".tooltip")),
                value, setter);
    }

    @SafeVarargs
    private static <T> OptionInstance<T> choice(String key, T value, Consumer<T> setter, Codec<T> codec,
                                                Function<T, Component> names, T... values) {
        return new OptionInstance<>("epicysm.settings." + key,
                OptionInstance.cachedConstantTooltip(Component.translatable("epicysm.settings." + key + ".tooltip")),
                (caption, v) -> names.apply(v),
                new OptionInstance.Enum<>(Arrays.asList(values), codec),
                value, setter);
    }
}
