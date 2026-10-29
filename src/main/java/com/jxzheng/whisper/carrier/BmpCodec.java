package com.jxzheng.whisper.carrier;

/** Uncompressed 24-bit and 32-bit BMP. Rows are stored bottom-up unless the height is negative. */
public final class BmpCodec {

    private BmpCodec() {
    }

    public static RasterImage read(byte[] file) {
        if (file == null || file.length < 54 || file[0] != 'B' || file[1] != 'M') {
            throw new IllegalArgumentException("bmp: not a BMP");
        }
        int pixelOffset = int32(file, 10);
        int dib = int32(file, 14);
        if (dib < 40) {
            throw new IllegalArgumentException("bmp: unsupported header");
        }
        int width = int32(file, 18);
        int heightRaw = int32(file, 22);
        boolean bottomUp = heightRaw > 0;
        int height = Math.abs(heightRaw);
        int planes = int16(file, 26);
        int bits = int16(file, 28);
        int compression = int32(file, 30);
        if (planes != 1 || compression != 0 || (bits != 24 && bits != 32)) {
            throw new IllegalArgumentException("bmp: only uncompressed 24-bit and 32-bit images are supported");
        }
        // BI_RGB stores a padding byte in 32-bit files. It is not an alpha channel.
        if (width <= 0 || height <= 0 || width > 20_000 || height > 20_000) {
            throw new IllegalArgumentException("bmp: unreasonable dimensions");
        }
        int spp = 3;
        int colorType = 2;
        int rowBytes = ((width * (bits / 8) + 3) / 4) * 4;
        if (pixelOffset < 0 || (long) pixelOffset + (long) rowBytes * height > file.length) {
            throw new IllegalArgumentException("bmp: truncated pixel data");
        }
        RasterImage image = new RasterImage();
        image.width = width;
        image.height = height;
        image.colorType = colorType;
        image.samples = new byte[width * height * spp];
        for (int y = 0; y < height; y++) {
            int srcRow = bottomUp ? height - 1 - y : y;
            int src = pixelOffset + srcRow * rowBytes;
            int dst = y * width * spp;
            for (int x = 0; x < width; x++) {
                int p = src + x * (bits / 8);
                image.samples[dst + x * spp] = file[p + 2];
                image.samples[dst + x * spp + 1] = file[p + 1];
                image.samples[dst + x * spp + 2] = file[p];
            }
        }
        return image;
    }

    public static byte[] write(RasterImage image) {
        int spp = image.spp();
        if (spp != 1 && spp != 2 && spp != 3 && spp != 4) {
            throw new IllegalArgumentException("bmp: need grey or RGB samples");
        }
        int bits = 24;
        int rowBytes = ((image.width * 3 + 3) / 4) * 4;
        int pixelOffset = 54;
        int size = pixelOffset + rowBytes * image.height;
        byte[] out = new byte[size];
        out[0] = 'B';
        out[1] = 'M';
        put32(out, 2, size);
        put32(out, 10, pixelOffset);
        put32(out, 14, 40);
        put32(out, 18, image.width);
        put32(out, 22, image.height);
        put16(out, 26, 1);
        put16(out, 28, bits);
        for (int y = 0; y < image.height; y++) {
            int src = y * image.width * spp;
            int dst = pixelOffset + (image.height - 1 - y) * rowBytes;
            for (int x = 0; x < image.width; x++) {
                int s = src + x * spp;
                int d = dst + x * 3;
                int red;
                int green;
                int blue;
                if (spp <= 2) {
                    red = green = blue = image.samples[s] & 0xff;
                } else {
                    red = image.samples[s] & 0xff;
                    green = image.samples[s + 1] & 0xff;
                    blue = image.samples[s + 2] & 0xff;
                }
                out[d] = (byte) blue;
                out[d + 1] = (byte) green;
                out[d + 2] = (byte) red;
            }
        }
        return out;
    }

    private static int int16(byte[] b, int i) {
        return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8);
    }

    private static int int32(byte[] b, int i) {
        return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8) | ((b[i + 2] & 0xff) << 16) | ((b[i + 3] & 0xff) << 24);
    }

    private static void put16(byte[] b, int i, int v) {
        b[i] = (byte) v;
        b[i + 1] = (byte) (v >>> 8);
    }

    private static void put32(byte[] b, int i, int v) {
        b[i] = (byte) v;
        b[i + 1] = (byte) (v >>> 8);
        b[i + 2] = (byte) (v >>> 16);
        b[i + 3] = (byte) (v >>> 24);
    }
}
