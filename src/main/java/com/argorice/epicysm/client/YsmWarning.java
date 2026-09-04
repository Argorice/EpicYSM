package com.argorice.epicysm.client;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.List;

import com.argorice.epicysm.EpicYsm;

/** Withdraws Yes Steve Model's start-up warning about Epic Fight. */
final class YsmWarning {
    private YsmWarning() {
    }

    static void withdraw() {
        try {
            // Forge keeps its warnings on the ModLoader instance; the static
            // fields are looked at as well in case a build moves them.
            Class<?> loader = Class.forName("net.minecraftforge.fml.ModLoader");
            Object instance = null;

            try {
                instance = loader.getMethod("get").invoke(null);
            } catch (Throwable ignored) {
            }

            int removed = 0;

            for (Field field : loader.getDeclaredFields()) {
                boolean isStatic = Modifier.isStatic(field.getModifiers());

                if (!List.class.isAssignableFrom(field.getType()) || (!isStatic && instance == null)) {
                    continue;
                }

                if (!field.trySetAccessible()) {
                    continue;
                }

                Object held = field.get(isStatic ? null : instance);

                if (!(held instanceof List<?> list)) {
                    continue;
                }

                synchronized (list) {
                    Iterator<?> it = list.iterator();

                    while (it.hasNext()) {
                        Object issue = it.next();

                        if (isYsmAboutEpicFight(issue)) {
                            try {
                                it.remove();
                                removed++;
                            } catch (UnsupportedOperationException e) {
                                return;
                            }
                        }
                    }
                }
            }

            if (removed > 0) {
                EpicYsm.LOGGER.info("Withdrew Yes Steve Model's start-up warning about Epic Fight ({}); this mod"
                        + " is what makes the two work together", removed);
            }
        } catch (Throwable t) {
            EpicYsm.LOGGER.debug("Could not withdraw Yes Steve Model's warning about Epic Fight", t);
        }
    }

    private static boolean isYsmAboutEpicFight(Object issue) {
        StringBuilder text = new StringBuilder(String.valueOf(issue));

        // Forge's ModLoadingWarning does not say much in toString(); its
        // formatted text and its fields (mod info, message key, arguments) do.
        try {
            text.append(' ').append(issue.getClass().getMethod("formatToString").invoke(issue));
        } catch (Throwable ignored) {
        }

        for (Class<?> type = issue.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible()) {
                    continue;
                }

                try {
                    Object value = field.get(issue);

                    if (value instanceof Object[] array) {
                        text.append(' ').append(java.util.Arrays.toString(array));
                    } else if (value != null) {
                        text.append(' ').append(value);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        String lower = text.toString().toLowerCase(java.util.Locale.ROOT);
        return lower.contains("yes_steve_model") && lower.contains("incompatible")
                && (lower.contains("epic fight") || lower.contains("epicfight"));
    }
}
