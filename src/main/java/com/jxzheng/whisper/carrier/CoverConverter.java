package com.jxzheng.whisper.carrier;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Turns a paletted, interlaced, or odd-depth cover into an 8-bit PNG raster
 * through ImageIO, then re-encodes it with {@link PngCodec}. The cover bytes
 * change; the hidden payload is not involved.
 */
public final class CoverConverter {

    private CoverConverter() {
    }

    public static byte[] toEightBitPng(byte[] input) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(input));
        if (image == null) {
            throw new IOException("Could not convert this cover to an 8-bit PNG");
        }
        RasterImage raster = new RasterImage();
        raster.width = image.getWidth();
        raster.height = image.getHeight();
        boolean alpha = image.getColorModel().hasAlpha();
        raster.colorType = alpha ? 6 : 2;
        int spp = raster.spp();
        raster.samples = new byte[raster.width * raster.height * spp];
        for (int y = 0; y < raster.height; y++) {
            for (int x = 0; x < raster.width; x++) {
                int argb = image.getRGB(x, y);
                int i = (y * raster.width + x) * spp;
                raster.samples[i] = (byte) ((argb >> 16) & 0xff);
                raster.samples[i + 1] = (byte) ((argb >> 8) & 0xff);
                raster.samples[i + 2] = (byte) (argb & 0xff);
                if (alpha) {
                    raster.samples[i + 3] = (byte) ((argb >> 24) & 0xff);
                }
            }
        }
        return PngCodec.encode(raster, false);
    }
}
