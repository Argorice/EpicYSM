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
            Class<?> loader = Class.forName("net.neoforged.fml.ModLoader");
            int removed = 0;

            for (Field field : loader.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) {
                    continue;
                }

                if (!field.trySetAccessible()) {
                    continue;
                }

                Object held = field.get(null);

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
        String text = String.valueOf(issue);
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("yes_steve_model") && lower.contains("incompatible")
                && (lower.contains("epic fight") || lower.contains("epicfight"));
    }
}
