package com.jxzheng.whisper.carrier;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Turns a paletted, interlaced, or odd-depth cover into an 8-bit PNG raster,
 * then re-encodes it with {@link PngCodec}. Greyscale stays greyscale: samples
 * are read from the raster (16-bit values keep the high byte) instead of
 * {@code BufferedImage.getRGB}, which converts through sRGB and lightens grey.
 */
public final class CoverConverter {

    private CoverConverter() {
    }

    public static byte[] toEightBitPng(byte[] input) throws IOException {
        int colorType = pngColorType(input);
        if (colorType == 0 || colorType == 4) {
            return greyToEightBit(input, colorType == 4);
        }
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

    private static byte[] greyToEightBit(byte[] input, boolean alpha) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("Could not convert this cover to an 8-bit PNG");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw new IOException("Could not convert this cover to an 8-bit PNG");
                }
                Raster raster = decoded.getRaster();
                int width = raster.getWidth();
                int height = raster.getHeight();
                int bands = raster.getNumBands();
                boolean keepAlpha = alpha && bands > 1;
                RasterImage image = new RasterImage();
                image.width = width;
                image.height = height;
                image.colorType = keepAlpha ? 4 : 0;
                int spp = image.spp();
                image.samples = new byte[width * height * spp];
                int bits = raster.getSampleModel().getSampleSize(0);
                int alphaBits = keepAlpha ? raster.getSampleModel().getSampleSize(1) : 8;
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int i = (y * width + x) * spp;
                        image.samples[i] = (byte) highByte(raster.getSample(x, y, 0), bits);
                        if (keepAlpha) {
                            image.samples[i + 1] = (byte) highByte(raster.getSample(x, y, 1), alphaBits);
                        }
                    }
                }
                return PngCodec.encode(image, false);
            } finally {
                reader.dispose();
            }
        }
    }

    private static int highByte(int sample, int bits) {
        if (bits > 8) {
            return (sample >> (bits - 8)) & 0xff;
        }
        if (bits < 8) {
            int max = (1 << bits) - 1;
            return max == 0 ? 0 : sample * 255 / max;
        }
        return sample & 0xff;
    }

    private static int pngColorType(byte[] input) {
        if (input == null || input.length < 26 || input[0] != (byte) 0x89 || input[1] != 'P') {
            return -1;
        }
        if (input[12] != 'I' || input[13] != 'H' || input[14] != 'D' || input[15] != 'R') {
            return -1;
        }
        return input[25] & 0xff;
    }
}
