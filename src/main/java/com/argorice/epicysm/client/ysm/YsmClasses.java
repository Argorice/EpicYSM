package com.argorice.epicysm.client.ysm;

import java.util.ArrayList;
import java.util.List;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.ModFileScanData;

/**
 * Names of the classes Yes Steve Model ships, taken from the loader's scan
 * of the mod rather than from the jar on disk.
 */
final class YsmClasses {
    private static final String YSM_PACKAGE = "com.elfmcys.";

    private static List<String> names;

    private YsmClasses() {
    }

    static synchronized List<String> names() {
        if (names != null) {
            return names;
        }

        List<String> found = new ArrayList<>();

        try {
            var modFile = ModList.get().getModFileById("yes_steve_model");
            ModFileScanData scan = modFile == null ? null : modFile.getFile().getScanResult();

            if (scan != null) {
                for (ModFileScanData.ClassData data : scan.getClasses()) {
                    String className = data.clazz().getClassName();

                    if (className.startsWith(YSM_PACKAGE)) {
                        found.add(className);
                    }
                }
            }
        } catch (Throwable t) {
            // Not installed the way the loader expects; nothing to list.
        }

        names = List.copyOf(found);
        return names;
    }
}
