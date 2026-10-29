package com.jxzheng.whisper.carrier;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Turns a paletted, interlaced, odd-depth, or non-PNG raster cover into an
 * 8-bit PNG. Greyscale stays greyscale: samples are read from the raster
 * (16-bit values keep the high byte) instead of {@code BufferedImage.getRGB},
 * which converts through sRGB and lightens grey. Colorimetric chunks and a
 * greyscale transparency key are carried onto the result.
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
        copyColorimetry(input, raster);
        return PngCodec.encode(raster, false);
    }

    private static byte[] greyToEightBit(byte[] input, boolean alpha) throws IOException {
        int bits = pngBitDepth(input);
        int trns = alpha ? -1 : greyTrnsKey(input, bits);
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
                boolean keyAlpha = trns >= 0;
                RasterImage image = new RasterImage();
                image.width = width;
                image.height = height;
                image.colorType = (keepAlpha || keyAlpha) ? 4 : 0;
                int spp = image.spp();
                image.samples = new byte[width * height * spp];
                int sampleBits = raster.getSampleModel().getSampleSize(0);
                int alphaBits = keepAlpha ? raster.getSampleModel().getSampleSize(Math.min(1, bands - 1)) : 8;
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int i = (y * width + x) * spp;
                        int raw = raster.getSample(x, y, 0);
                        image.samples[i] = (byte) highByte(raw, sampleBits > 0 ? sampleBits : bits);
                        if (keyAlpha) {
                            image.samples[i + 1] = (byte) (raw == trns ? 0 : 255);
                        } else if (keepAlpha) {
                            image.samples[i + 1] = (byte) highByte(raster.getSample(x, y, 1), alphaBits);
                        }
                    }
                }
                copyColorimetry(input, image);
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
        if (!pngHeader(input)) {
            return -1;
        }
        return input[25] & 0xff;
    }

    private static int pngBitDepth(byte[] input) {
        if (!pngHeader(input)) {
            return 8;
        }
        return input[24] & 0xff;
    }

    private static boolean pngHeader(byte[] input) {
        return input != null && input.length >= 26 && input[0] == (byte) 0x89 && input[1] == 'P'
                && input[12] == 'I' && input[13] == 'H' && input[14] == 'D' && input[15] == 'R';
    }

    /** Greyscale tRNS key in the same units as the decoded raster sample, or -1. */
    private static int greyTrnsKey(byte[] input, int bits) {
        int i = 8;
        while (i + 12 <= input.length) {
            int n = be32(input, i);
            if (n < 0 || i + 12L + n > input.length) {
                return -1;
            }
            String type = new String(input, i + 4, 4, StandardCharsets.US_ASCII);
            if ("IEND".equals(type)) {
                return -1;
            }
            if ("tRNS".equals(type) && n >= 2) {
                int key = ((input[i + 8] & 0xff) << 8) | (input[i + 9] & 0xff);
                if (bits >= 16) {
                    return key;
                }
                if (bits <= 0 || bits >= 31) {
                    return key;
                }
                return key & ((1 << bits) - 1);
            }
            i += 12 + n;
        }
        return -1;
    }

    private static void copyColorimetry(byte[] input, RasterImage image) {
        if (!pngHeader(input)) {
            return;
        }
        int i = 8;
        while (i + 12 <= input.length) {
            int n = be32(input, i);
            if (n < 0 || i + 12L + n > input.length) {
                return;
            }
            String type = new String(input, i + 4, 4, StandardCharsets.US_ASCII);
            if ("IEND".equals(type)) {
                return;
            }
            if ("gAMA".equals(type) || "sRGB".equals(type) || "iCCP".equals(type)) {
                image.ancillary.add(new RasterImage.Chunk(type, Arrays.copyOfRange(input, i + 8, i + 8 + n)));
            }
            i += 12 + n;
        }
    }

    private static int be32(byte[] b, int i) {
        return ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }
}
