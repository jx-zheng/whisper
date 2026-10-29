package com.jxzheng.whisper.schemes;

/**
 * Per-embed settings that are not part of the carrier.
 *
 * <p>The legacy Zhang–Tang walk still takes the stego passphrase as a string.
 * Adaptive schemes ignore it and use the derived visit seed instead.
 */
public record EmbedOptions(String stegoKey, RatePreset preset, boolean keepMetadata, String outputFormat) {

    public EmbedOptions {
        if (stegoKey == null) {
            throw new IllegalArgumentException(
                    "stegoKey must not be null; use an empty string for the public default walk");
        }
        if (preset == null) {
            preset = RatePreset.STANDARD;
        }
        if (outputFormat == null || outputFormat.isBlank()) {
            outputFormat = "png";
        }
    }

    public EmbedOptions(String stegoKey, RatePreset preset, boolean keepMetadata) {
        this(stegoKey, preset, keepMetadata, "png");
    }

    public static EmbedOptions legacy(String stegoKey) {
        return new EmbedOptions(stegoKey, RatePreset.STANDARD, false, "png");
    }
}
