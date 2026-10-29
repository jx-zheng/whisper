package com.jxzheng.whisper.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

final class UiPrefs {

    private static final Preferences PREFS = Preferences.userNodeForPackage(WhisperApp.class);

    private UiPrefs() {
    }

    static boolean darkTheme() {
        try {
            return PREFS.getBoolean("dark", false);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    static void darkTheme(boolean dark) {
        try {
            PREFS.putBoolean("dark", dark);
        } catch (RuntimeException ignored) {
            // Preferences can be unavailable in locked-down environments.
        }
    }

    static Path lastDirectory() {
        try {
            String stored = PREFS.get("lastDir", System.getProperty("user.home", "."));
            Path path = Path.of(stored);
            if (Files.isDirectory(path)) {
                return path;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the home directory.
        }
        return Path.of(System.getProperty("user.home", "."));
    }

    static void rememberDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try {
            PREFS.put("lastDir", directory.toString());
        } catch (RuntimeException ignored) {
            // Ignore a missing preferences store.
        }
    }
}
