package com.jxzheng.whisper.ops;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Decides whether an extracted payload can be shown as text.
 * Binary payloads get a hex dump so the UI never pretends they are a string.
 */
public final class PayloadPreview {

    private static final int HEX_LIMIT = 4096;

    private PayloadPreview() {
    }

    public record View(boolean text, String content) {
    }

    public static View of(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (payload.length == 0) {
            return new View(true, "");
        }
        String text = new String(payload, StandardCharsets.UTF_8);
        if (!Arrays.equals(payload, text.getBytes(StandardCharsets.UTF_8)) || !printable(text)) {
            return new View(false, hex(payload));
        }
        return new View(true, text);
    }

    public static String hex(byte[] payload) {
        StringBuilder out = new StringBuilder();
        int limit = Math.min(payload.length, HEX_LIMIT);
        for (int offset = 0; offset < limit; offset += 16) {
            if (offset > 0) {
                out.append('\n');
            }
            out.append(String.format(Locale.ROOT, "%04x  ", offset));
            int row = Math.min(16, limit - offset);
            for (int column = 0; column < row; column++) {
                if (column == 8) {
                    out.append(' ');
                }
                out.append(String.format(Locale.ROOT, "%02x ", payload[offset + column] & 0xFF));
            }
        }
        if (payload.length > limit) {
            out.append("\n… ").append(payload.length - limit).append(" more byte(s)");
        }
        return out.toString();
    }

    private static boolean printable(String text) {
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (codePoint != '\n' && codePoint != '\r' && codePoint != '\t' && Character.isISOControl(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }
}
