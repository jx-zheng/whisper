package com.jxzheng.whisper.gui;

import java.util.List;

import javafx.scene.text.Font;

final class UiFonts {

    static String sans = "System";
    static String mono = "Monospaced";

    private UiFonts() {
    }

    static void load() {
        List<String> families = Font.getFamilies();
        sans = first(families, "System", "Segoe UI", "San Francisco", "Helvetica Neue", "Ubuntu",
                "Noto Sans", "Liberation Sans", "DejaVu Sans");
        mono = first(families, "Monospaced", "JetBrains Mono", "Liberation Mono", "DejaVu Sans Mono",
                "Menlo", "Consolas", "Monaco");
    }

    private static String first(List<String> families, String fallback, String... names) {
        for (String name : names) {
            if (families.contains(name)) {
                return name;
            }
        }
        return fallback;
    }
}
