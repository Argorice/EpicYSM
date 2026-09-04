package com.argorice.epicysm.client;

import com.argorice.epicysm.EpicYsm;

/** Detailed log lines: at INFO with diagnostics on, at DEBUG otherwise. */
public final class Diag {
    private Diag() {
    }

    public static boolean on() {
        try {
            return EpicYsmConfig.diagnostics();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void info(String message, Object... args) {
        if (on()) {
            EpicYsm.LOGGER.info(message, args);
        } else {
            EpicYsm.LOGGER.debug(message, args);
        }
    }
}
