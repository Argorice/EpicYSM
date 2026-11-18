package com.argorice.epicysm.client.ysm;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * The bones of a model's face.
 *
 * Models carry their expressions as planes a bone each - several mouths,
 * several pairs of eyes, eyebrows, blush - and the model's own animation
 * shows one and parks the rest, by moving them, by turning them, by
 * scaling them to nothing. Held still at their rest, as the rest of a
 * model is while Epic Fight poses the body, the parked ones came back and
 * the face was a heap of every expression at once. These bones are never
 * held: the face stays Yes Steve Model's, and goes on blinking in battle.
 *
 * Known by name, the way the model's own weapons are, and whole: a face
 * bone takes everything under it along.
 */
final class FaceBones {
    private FaceBones() {
    }

    /** Words anywhere in a bone's name that mean it is part of the face. */
    private static final List<String> WORDS = List.of(
            "expression", "face", "eye", "mouth", "eyebrow", "blink", "emotion", "cheek", "blush",
            "tear", "pupil", "iris", "tongue", "nose", "freckle");

    /** Whether this one bone is a bone of the face, by its name alone. */
    static boolean isFace(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        String lower = name.toLowerCase(Locale.ROOT);

        // "face" inside a word that is not one.
        if (lower.contains("surface") || lower.contains("facing") || lower.contains("faceplate")) {
            return false;
        }

        for (String word : WORDS) {
            int at = lower.indexOf(word);

            if (at < 0) {
                continue;
            }

            // "tear" is also what LeftEar, RightEar and CatEar spell across
            // two words. It counts where a word begins with it.
            if (word.equals("tear") && !startsAWord(name, at)) {
                continue;
            }

            return true;
        }

        return false;
    }

    /** Whether a word of the name begins here: at its start, at a capital, or after something that is not a letter. */
    private static boolean startsAWord(String name, int at) {
        return at == 0 || Character.isUpperCase(name.charAt(at)) || !Character.isLetter(name.charAt(at - 1));
    }

    /**
     * Every bone of the face in a model: the ones named so, and everything
     * under them. Null when the model's tree is not known.
     */
    @Nullable
    static Set<String> of(@Nullable List<YsmLiveSkeleton.LiveBone> bones) {
        if (bones == null || bones.isEmpty()) {
            return null;
        }

        Map<String, String> parentOf = new java.util.HashMap<>();
        Set<String> face = new HashSet<>();

        for (YsmLiveSkeleton.LiveBone bone : bones) {
            if (bone.parent() != null) {
                parentOf.put(bone.name(), bone.parent());
            }

            if (isFace(bone.name())) {
                face.add(bone.name());
            }
        }

        if (face.isEmpty()) {
            return face;
        }

        // Children of a face bone are the face as well, however they are named.
        for (YsmLiveSkeleton.LiveBone bone : bones) {
            String at = bone.parent();
            int depth = 0;

            while (at != null && depth++ < 64) {
                if (face.contains(at)) {
                    face.add(bone.name());
                    break;
                }

                at = parentOf.get(at);
            }
        }

        return face;
    }
}
