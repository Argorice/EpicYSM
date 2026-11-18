package com.argorice.epicysm.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import java.lang.reflect.Method;

import javax.annotation.Nullable;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import yesman.epicfight.mixin.client.MixinEntityRenderer;
import yesman.epicfight.mixin.client.MixinLivingEntityRenderer;

import com.argorice.epicysm.client.compat.EpicFightApi;

/**
 * The parts of a vanilla renderer Epic Fight opens up for its own renderers:
 * whether a name is shown and drawing it, whether the body can be seen, the
 * render type it is drawn with. Epic Fight keeps them in
 * yesman.epicfight.mixin.client from 20.11.3 on and in
 * yesman.epicfight.mixin before; the methods are the same either way, and
 * the old ones are found by name.
 */
final class RendererAccess {
    private static final boolean CURRENT = EpicFightApi.classExists("yesman.epicfight.mixin.client.MixinEntityRenderer");
    private static final String OLD_ENTITY = "yesman.epicfight.mixin.MixinEntityRenderer";
    private static final String OLD_LIVING = "yesman.epicfight.mixin.MixinLivingEntityRenderer";

    @Nullable
    private static Method shouldShowName;
    @Nullable
    private static Method renderNameTag;
    @Nullable
    private static Method isBodyVisible;
    @Nullable
    private static Method getRenderType;
    private static boolean looked;

    private RendererAccess() {
    }

    static boolean shouldShowName(Object renderer, Entity entity) {
        if (CURRENT) {
            return Current.shouldShowName(renderer, entity);
        }

        Object answer = call(lookUp() ? shouldShowName : null, renderer, entity);
        return answer instanceof Boolean shown && shown;
    }

    static void renderNameTag(Object renderer, Entity entity, Component name, PoseStack poseStack, MultiBufferSource buffers, int light) {
        if (CURRENT) {
            Current.renderNameTag(renderer, entity, name, poseStack, buffers, light);
            return;
        }

        call(lookUp() ? renderNameTag : null, renderer, entity, name, poseStack, buffers, light);
    }

    static boolean isBodyVisible(Object renderer, LivingEntity entity) {
        if (CURRENT) {
            return Current.isBodyVisible(renderer, entity);
        }

        Object answer = call(lookUp() ? isBodyVisible : null, renderer, entity);
        return !(answer instanceof Boolean visible) || visible;
    }

    @Nullable
    static RenderType renderType(Object renderer, LivingEntity entity, boolean visible, boolean visibleToPlayer, boolean glowing) {
        if (CURRENT) {
            return Current.renderType(renderer, entity, visible, visibleToPlayer, glowing);
        }

        return call(lookUp() ? getRenderType : null, renderer, entity, visible, visibleToPlayer, glowing) instanceof RenderType type ? type : null;
    }

    private static synchronized boolean lookUp() {
        if (!looked) {
            looked = true;

            try {
                Class<?> entity = Class.forName(OLD_ENTITY);
                Class<?> living = Class.forName(OLD_LIVING);
                shouldShowName = entity.getMethod("invokeShouldShowName", Entity.class);
                renderNameTag = entity.getMethod("invokeRenderNameTag", Entity.class, Component.class, PoseStack.class,
                        MultiBufferSource.class, int.class);
                isBodyVisible = living.getMethod("invokeIsBodyVisible", LivingEntity.class);
                getRenderType = living.getMethod("invokeGetRenderType", LivingEntity.class, boolean.class, boolean.class, boolean.class);
            } catch (Throwable t) {
                com.argorice.epicysm.EpicYsm.LOGGER.warn("Epic Fight: the openings it makes in vanilla renderers were not found;"
                        + " names are not drawn over converted models, and bodies are drawn as if seen", t);
            }
        }

        return true;
    }

    @Nullable
    private static Object call(@Nullable Method method, Object target, Object... arguments) {
        if (method == null) {
            return null;
        }

        try {
            return method.invoke(target, arguments);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The openings where Epic Fight keeps them now. Only loaded where they are there. */
    private static final class Current {
        static boolean shouldShowName(Object renderer, Entity entity) {
            return ((MixinEntityRenderer) renderer).invokeShouldShowName(entity);
        }

        static void renderNameTag(Object renderer, Entity entity, Component name, PoseStack poseStack, MultiBufferSource buffers, int light) {
            ((MixinEntityRenderer) renderer).invokeRenderNameTag(entity, name, poseStack, buffers, light);
        }

        static boolean isBodyVisible(Object renderer, LivingEntity entity) {
            return ((MixinLivingEntityRenderer) renderer).invokeIsBodyVisible(entity);
        }

        static RenderType renderType(Object renderer, LivingEntity entity, boolean visible, boolean visibleToPlayer, boolean glowing) {
            return ((MixinLivingEntityRenderer) renderer).invokeGetRenderType(entity, visible, visibleToPlayer, glowing);
        }
    }
}
