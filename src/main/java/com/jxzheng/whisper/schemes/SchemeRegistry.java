package com.jxzheng.whisper.schemes;

import java.util.List;
import java.util.Locale;

import com.jxzheng.whisper.schemes.adaptive.HillStcScheme;
import com.jxzheng.whisper.schemes.adaptive.UerdStcScheme;

/**
 * Embed schemes. Blank or {@code auto} follows the cover format.
 * Zhang–Tang aliases remain so extract can name the 0.9 reveal path.
 */
public final class SchemeRegistry {

    private static final SchemeRegistry INSTANCE = new SchemeRegistry(List.of(
            new HillStcScheme(), new UerdStcScheme()));

    private final List<StegoScheme> schemes;

    public SchemeRegistry(List<StegoScheme> schemes) {
        this.schemes = List.copyOf(schemes);
    }

    public static SchemeRegistry shared() {
        return INSTANCE;
    }

    public List<StegoScheme> all() {
        return schemes;
    }

    public List<String> ids() {
        return schemes.stream().map(StegoScheme::id).toList();
    }

    /**
     * One mapping. Blank and {@code auto} become hill-stc, or uerd-stc when
     * {@code format} is {@code jpeg}.
     */
    public static String canonical(String scheme, String format) {
        String name = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT).trim();
        if (name.isEmpty() || "auto".equals(name)) {
            return "jpeg".equals(format) ? UerdStcScheme.ID : HillStcScheme.ID;
        }
        return switch (name) {
            case "hill", "hill-stc" -> HillStcScheme.ID;
            case "uerd", "uerd-stc", "jpeg" -> UerdStcScheme.ID;
            case "zhang-tang", "zhangtang", "zt" -> "zhang-tang";
            default -> throw new IllegalArgumentException("Unsupported scheme: " + scheme);
        };
    }
}
