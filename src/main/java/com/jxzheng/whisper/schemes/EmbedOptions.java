package com.jxzheng.whisper.schemes;

/**
 * Per-embed settings that are not part of the carrier.
 * The visit seed, not a passphrase string, selects adaptive bits.
 */
public record EmbedOptions(RatePreset preset, boolean keepMetadata, String outputFormat) {

    public EmbedOptions {
        if (preset == null) {
            preset = RatePreset.STANDARD;
        }
        if (outputFormat == null || outputFormat.isBlank()) {
            outputFormat = "png";
        }
    }

    public EmbedOptions(RatePreset preset, boolean keepMetadata) {
        this(preset, keepMetadata, "png");
    }
}
