package com.jxzheng.whisper.carrier;

import java.util.ArrayList;
import java.util.List;

/**
 * 8-bit raster in sample order, without per-row PNG filter bytes.
 * {@code colorType} uses PNG values: 0 grey, 2 RGB, 4 grey+alpha, 6 RGBA.
 */
public final class RasterImage {

    public int width;
    public int height;
    public int colorType;
    public byte[] samples;
    public final List<Chunk> ancillary = new ArrayList<>();

    public record Chunk(String type, byte[] data) {
    }

    public int spp() {
        return switch (colorType) {
            case 0 -> 1;
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> 0;
        };
    }

    public int stride() {
        return width * spp();
    }
}
