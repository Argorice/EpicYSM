package com.argorice.epicysm.client.ysm;

import javax.annotation.Nullable;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Which model and texture a player has chosen in Yes Steve Model, read
 * from what Yes Steve Model itself keeps on the player: it stores the
 * choice as data attached to the entity, synced to every client, and
 * writes it out under plain names when the entity is saved. Nothing of
 * the obfuscated jar is needed for it.
 */
public final class YsmSelection {
    /** The compound Yes Steve Model saves the choice under, on any loader. */
    private static final String KEY = "yes_steve_model:model_id";

    /** @param texture the texture chosen within the model, as Yes Steve Model names it */
    public record Selection(String modelId, String texture, boolean disabled) {
    }

    private YsmSelection() {
    }

    @Nullable
    public static Selection of(AbstractClientPlayer player) {
        try {
            CompoundTag found = find(player.saveWithoutId(new CompoundTag()), 0);

            if (found == null || !found.contains("model_id", Tag.TAG_STRING)) {
                return null;
            }

            String modelId = found.getString("model_id");
            return modelId.isEmpty() ? null
                    : new Selection(modelId, found.getString("select_texture"), found.getBoolean("disabled"));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The compound, wherever the loader put it: under "ForgeCaps" on one,
     * "neoforge:attachments" on the other, and not assumed to stay there.
     */
    @Nullable
    private static CompoundTag find(CompoundTag tag, int depth) {
        if (tag.contains(KEY, Tag.TAG_COMPOUND)) {
            return tag.getCompound(KEY);
        }

        if (depth >= 2) {
            return null;
        }

        for (String key : tag.getAllKeys()) {
            if (tag.contains(key, Tag.TAG_COMPOUND)) {
                CompoundTag found = find(tag.getCompound(key), depth + 1);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }
}
