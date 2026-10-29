package com.jxzheng.whisper.payload;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Basename-only file names. Never a path, never a Windows reserved device.
 */
public final class NameSanitizer {

    static final int MAX_BYTES = 255;
    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private NameSanitizer() {
    }

    public static String sanitize(String name, String mime) {
        String base = basename(name);
        String cleaned = strip(base);
        if (cleaned.isEmpty() || reserved(cleaned)) {
            return fallback(mime);
        }
        return truncate(cleaned);
    }

    public static String extensionFor(String mime) {
        if (mime == null) {
            return "bin";
        }
        return switch (mime.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            case "image/webp" -> "webp";
            case "application/pdf" -> "pdf";
            case "text/plain" -> "txt";
            case "application/zip" -> "zip";
            default -> "bin";
        };
    }

    public static String mime(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < 100; i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c < 0x7f && c != '"' && c != '\\') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String basename(String name) {
        if (name == null) {
            return "";
        }
        String s = name.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        return s;
    }

    private static String strip(String name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                continue;
            }
            if ("<>:\"/\\|?*".indexOf(c) >= 0) {
                continue;
            }
            out.append(c);
        }
        int start = 0;
        while (start < out.length() && (out.charAt(start) == '.' || out.charAt(start) == ' ')) {
            start++;
        }
        return out.substring(start).trim();
    }

    private static boolean reserved(String name) {
        String stem = name;
        int dot = stem.indexOf('.');
        if (dot >= 0) {
            stem = stem.substring(0, dot);
        }
        return RESERVED.contains(stem.toUpperCase(Locale.ROOT));
    }

    private static String truncate(String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return name;
        }
        int end = MAX_BYTES;
        while (end > 0 && (bytes[end] & 0xc0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static String fallback(String mime) {
        return "whisper-payload." + extensionFor(mime);
    }
}
