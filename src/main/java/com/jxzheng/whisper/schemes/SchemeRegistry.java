package com.jxzheng.whisper.schemes;

import java.util.List;
import java.util.Locale;

import com.jxzheng.whisper.schemes.adaptive.HillStcScheme;
import com.jxzheng.whisper.schemes.adaptive.UerdStcScheme;

/**
 * Ordered scheme list. Combo boxes and the CLI read this rather than a hard-coded name.
 */
public final class SchemeRegistry {

    private static final SchemeRegistry INSTANCE = new SchemeRegistry(List.of(
            new HillStcScheme(), new UerdStcScheme(), new ZhangTangStegoScheme()));

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

    public StegoScheme require(String name) {
        String canonical = canonical(name);
        for (StegoScheme scheme : schemes) {
            if (scheme.id().equals(canonical)) {
                return scheme;
            }
        }
        throw new IllegalArgumentException("Unsupported scheme: " + canonical);
    }

    public static String canonical(String scheme) {
        String name = (scheme == null || scheme.isBlank())
                ? ZhangTangStegoScheme.ID
                : scheme.toLowerCase(Locale.ROOT).trim();
        return switch (name) {
            case "zhang-tang", "zhangtang", "zt" -> ZhangTangStegoScheme.ID;
            case "hill", "hill-stc" -> HillStcScheme.ID;
            case "uerd", "uerd-stc", "jpeg" -> UerdStcScheme.ID;
            case "auto" -> "auto";
            default -> name;
        };
    }
}
