package com.argorice.epicysm.client;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import com.argorice.epicysm.client.ysm.YsmSkeletonOverlay;

/** The mod's settings, as a scrollable vanilla-style options screen. */
public final class EpicYsmSettingsScreen extends Screen {
    @Nullable
    private final Screen parent;
    private OptionsList list;

    public EpicYsmSettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("epicysm.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.list = new OptionsList(this.minecraft, this.width, this.height, 32, this.height - 32, 25);

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

        this.addWidget(this.list);
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 27, 200, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        this.list.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
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
