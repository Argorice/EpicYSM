package com.argorice.epicysm.client.ysm;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Puts a pose stack back the way it was after code that was handed it
 * threw halfway through.
 *
 * A renderer that fails between its push and its pop leaves its entries
 * on the stack. Vanilla checks the stack at the end of every frame and
 * stops the game with "Pose stack not empty" - a crash that names no mod
 * and hides the exception that caused it. Wherever this mod catches a
 * renderer's exception instead of letting it through, it also drops what
 * that renderer left behind.
 */
public final class PoseStacks {
    private PoseStacks() {
    }

    /** The top of the stack now, to come back to. */
    public static PoseStack.Pose mark(PoseStack poseStack) {
        return poseStack.last();
    }

    /** Pops whatever was pushed since the mark and not popped. */
    public static void unwind(PoseStack poseStack, PoseStack.Pose mark) {
        // The bottom entry is never popped, whatever the mark says.
        while (poseStack.last() != mark && !poseStack.clear()) {
            poseStack.popPose();
        }
    }
}
