package com.argorice.epicysm.client.ysm;

import java.lang.module.ModuleReader;
import java.lang.module.ResolvedModule;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.ModFileScanData;

import com.argorice.epicysm.EpicYsm;

/**
 * Names of the classes Yes Steve Model ships, taken from what the loader
 * already knows about the mod: its scan data first, its module second.
 */
final class YsmClasses {
    private static final String YSM_PACKAGE = "com.elfmcys.";
    private static final String MOD_ID = "yes_steve_model";

    private static List<String> names;

    private YsmClasses() {
    }

    /** @param sample any object of Yes Steve Model's, to find its module by */
    static synchronized List<String> names(Object sample) {
        if (names != null) {
            return names;
        }

        List<String> found = fromScan();
        String route = "scan";

        if (found.isEmpty()) {
            found = fromModule(sample);
            route = "module";
        }

        if (found.isEmpty()) {
            // Nothing yet; try again next time rather than remembering a blank.
            EpicYsm.LOGGER.warn("Yes Steve Model: none of its classes could be listed, so its live model cannot be found");
            return List.of();
        }

        EpicYsm.LOGGER.info("Yes Steve Model: {} classes known ({})", found.size(), route);
        names = List.copyOf(found);
        return names;
    }

    private static List<String> fromScan() {
        List<String> found = new ArrayList<>();

        try {
            var modFile = ModList.get().getModFileById(MOD_ID);
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
            EpicYsm.LOGGER.debug("Yes Steve Model scan data unavailable", t);
        }

        return found;
    }

    /** The module the loader built for the mod, listed through the module system. */
    private static List<String> fromModule(Object sample) {
        List<String> found = new ArrayList<>();

        try {
            if (sample == null) {
                return found;
            }

            Module module = sample.getClass().getModule();

            if (!module.isNamed() || module.getLayer() == null) {
                return found;
            }

            Optional<ResolvedModule> resolved = module.getLayer().configuration().findModule(module.getName());

            if (resolved.isEmpty()) {
                return found;
            }

            try (ModuleReader reader = resolved.get().reference().open(); Stream<String> entries = reader.list()) {
                for (String entry : (Iterable<String>) entries::iterator) {
                    if (!entry.endsWith(".class") || entry.equals("module-info.class")) {
                        continue;
                    }

                    String className = entry.substring(0, entry.length() - 6).replace('/', '.');

                    if (className.startsWith(YSM_PACKAGE)) {
                        found.add(className);
                    }
                }
            }
        } catch (Throwable t) {
            EpicYsm.LOGGER.debug("Yes Steve Model module unavailable", t);
        }

        return found;
    }
}
