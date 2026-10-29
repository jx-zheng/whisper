package com.jxzheng.whisper.schemes;

/**
 * How much user content a cover can hold at one preset.
 *
 * <p>{@code maxBytes} is the hard cap. {@code recommendedBytes} is the size the
 * meter treats as the comfortable zone. Both are content bytes after framing
 * overhead, or raw Zhang–Tang payload bytes for the legacy scheme.
 */
public record Capacity(int maxBytes, int recommendedBytes, String unit, double rate) {

    public static Capacity none(String unit) {
        return new Capacity(0, 0, unit, 0);
    }
}
