package com.jxzheng.whisper.schemes;

/**
 * Embedding-rate presets. Reveal tries all three, so the recipient does not
 * have to know which one the sender used.
 */
public enum RatePreset {
    STANDARD(0.10, "Standard"),
    LOW(0.05, "Low"),
    PARANOID(0.02, "Paranoid");

    private final double rate;
    private final String displayName;

    RatePreset(double rate, String displayName) {
        this.rate = rate;
        this.displayName = displayName;
    }

    public double rate() {
        return rate;
    }

    public String displayName() {
        return displayName;
    }

    public static RatePreset fromId(String id) {
        if (id == null || id.isBlank()) {
            return STANDARD;
        }
        return switch (id.toLowerCase(java.util.Locale.ROOT).trim()) {
            case "standard", "std" -> STANDARD;
            case "low" -> LOW;
            case "paranoid" -> PARANOID;
            default -> throw new IllegalArgumentException("Unknown rate preset: " + id);
        };
    }
}
