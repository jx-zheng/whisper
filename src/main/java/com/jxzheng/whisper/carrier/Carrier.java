package com.jxzheng.whisper.carrier;

import java.awt.image.BufferedImage;

/**
 * A decoded cover. Phase 1 stores the {@link BufferedImage} Zhang–Tang already
 * uses. Adaptive schemes add sample and coefficient planes on this type.
 */
public final class Carrier {

    private final BufferedImage image;
    private final RasterImage raster;
    private final byte[] encoded;
    private final String format;

    private Carrier(BufferedImage image, RasterImage raster, byte[] encoded, String format) {
        this.image = image;
        this.raster = raster;
        this.encoded = encoded;
        this.format = format;
    }

    public static Carrier of(BufferedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        return new Carrier(image, null, null, null);
    }

    public static Carrier raster(RasterImage raster, byte[] encoded, String format) {
        if (raster == null) {
            throw new IllegalArgumentException("raster must not be null");
        }
        return new Carrier(null, raster, encoded, format);
    }

    public BufferedImage image() {
        if (image == null) {
            throw new IllegalStateException("this carrier has no BufferedImage");
        }
        return image;
    }

    public boolean hasImage() {
        return image != null;
    }

    public RasterImage raster() {
        return raster;
    }

    public byte[] encoded() {
        return encoded;
    }

    public String format() {
        return format;
    }

    public int width() {
        return image != null ? image.getWidth() : raster.width;
    }

    public int height() {
        return image != null ? image.getHeight() : raster.height;
    }
}
