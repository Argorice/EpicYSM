package com.argorice.epicysm.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraftforge.client.event.RenderLivingEvent;
import yesman.epicfight.client.events.engine.RenderEngine;

import com.argorice.epicysm.client.ysm.YsmRenderBridge;

/**
 * Epic Fight's render hook stays out of a body this mod is drawing through
 * Yes Steve Model. That draw fires the render event a second time, from
 * inside; for a player Epic Fight asks this mod first and is told not to
 * draw, for a maid it asks nobody and would cancel the inner draw and
 * leave nothing on screen.
 */
@Mixin(value = RenderEngine.Events.class, remap = false)
public abstract class RenderEngineMixin {
    @Inject(method = "renderLivingEvent(Lnet/minecraftforge/client/event/RenderLivingEvent$Pre;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void epicysm$stayOutOfNestedDraw(RenderLivingEvent.Pre<?, ?> event, CallbackInfo ci) {
        if (YsmRenderBridge.inside()) {
            ci.cancel();
        }
    }
}
