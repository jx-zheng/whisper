package com.jxzheng.whisper.carrier;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 8-bit non-interlaced PNG. IDAT chunks are 8 KiB. Text and EXIF are dropped
 * unless metadata is kept. Colorimetric chunks on the whitelist are kept.
 */
public final class PngCodec {

    public static final int IDAT = 8 * 1024;
    private static final byte[] SIG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final int MAX_DIM = 20_000;
    private static final long MAX_PIXELS = 40_000_000L;

    private PngCodec() {
    }

    public static RasterImage decode(byte[] file) {
        if (file == null || file.length < SIG.length + 12 || !matchesSig(file)) {
            throw new IllegalArgumentException("png: not a PNG");
        }
        RasterImage image = new RasterImage();
        byte[] idat = new byte[0];
        boolean ihdr = false;
        int i = SIG.length;
        while (i + 12 <= file.length) {
            int n = int32(file, i);
            if (n < 0 || i + 12L + n > file.length) {
                throw new IllegalArgumentException("png: truncated chunk");
            }
            String type = new String(file, i + 4, 4, StandardCharsets.US_ASCII);
            int dataAt = i + 8;
            long crc = int32(file, i + 8 + n) & 0xffffffffL;
            CRC32 sum = new CRC32();
            sum.update(file, i + 4, 4 + n);
            if (sum.getValue() != crc) {
                throw new IllegalArgumentException("png: bad CRC on " + type);
            }
            i += 12 + n;
            switch (type) {
                case "IHDR" -> {
                    if (ihdr) {
                        throw new IllegalArgumentException("png: duplicate IHDR");
                    }
                    parseIhdr(image, file, dataAt, n);
                    ihdr = true;
                }
                case "IDAT" -> idat = concat(idat, file, dataAt, n);
                case "IEND" -> {
                    if (!ihdr) {
                        throw new IllegalArgumentException("png: missing IHDR");
                    }
                    image.samples = inflate(image, idat);
                    return image;
                }
                case "PLTE" -> throw new IllegalArgumentException(
                        "png: paletted images are not supported; convert to RGB or greyscale");
                default -> image.ancillary.add(new RasterImage.Chunk(type, Arrays.copyOfRange(file, dataAt, dataAt + n)));
            }
        }
        throw new IllegalArgumentException("png: missing IEND");
    }

    public static byte[] encode(RasterImage image, boolean keepMetadata) {
        return encode(image, keepMetadata, IDAT);
    }

    public static byte[] encode(RasterImage image, boolean keepMetadata, int idatSize) {
        if (image == null || image.spp() == 0) {
            throw new IllegalArgumentException("png: unsupported color type");
        }
        int stride = image.stride();
        if (image.width <= 0 || image.height <= 0 || image.samples == null || image.samples.length != image.height * stride) {
            throw new IllegalArgumentException("png: sample buffer does not match the dimensions");
        }
        byte[] filtered = filterAll(image.samples, image.height, stride, image.spp());
        byte[] compressed = deflate(filtered);
        if (idatSize <= 0) {
            idatSize = IDAT;
        }
        int chunks = Math.max(1, (compressed.length + idatSize - 1) / idatSize);
        int ancillary = 0;
        for (RasterImage.Chunk chunk : image.ancillary) {
            if (keep(chunk.type(), keepMetadata)) {
                ancillary += 12 + chunk.data().length;
            }
        }
        byte[] out = new byte[SIG.length + 12 + 13 + ancillary + chunks * 12 + compressed.length + 12];
        int p = 0;
        System.arraycopy(SIG, 0, out, p, SIG.length);
        p += SIG.length;
        byte[] ihdr = new byte[13];
        put32(ihdr, 0, image.width);
        put32(ihdr, 4, image.height);
        ihdr[8] = 8;
        ihdr[9] = (byte) image.colorType;
        p = chunk(out, p, "IHDR", ihdr);
        for (RasterImage.Chunk chunk : image.ancillary) {
            if (keep(chunk.type(), keepMetadata)) {
                p = chunk(out, p, chunk.type(), chunk.data());
            }
        }
        if (compressed.length == 0) {
            p = chunk(out, p, "IDAT", new byte[0]);
        } else {
            for (int off = 0; off < compressed.length; off += idatSize) {
                int end = Math.min(off + idatSize, compressed.length);
                p = chunk(out, p, "IDAT", Arrays.copyOfRange(compressed, off, end));
            }
        }
        p = chunk(out, p, "IEND", new byte[0]);
        return Arrays.copyOf(out, p);
    }

    public static int[] idatLengths(byte[] file) {
        if (!matchesSig(file)) {
            throw new IllegalArgumentException("png: not a PNG");
        }
        int[] lens = new int[8];
        int count = 0;
        int i = 8;
        while (i + 12 <= file.length) {
            int n = int32(file, i);
            if (i + 12L + n > file.length) {
                throw new IllegalArgumentException("png: truncated chunk");
            }
            String type = new String(file, i + 4, 4, StandardCharsets.US_ASCII);
            if ("IDAT".equals(type)) {
                if (count == lens.length) {
                    lens = Arrays.copyOf(lens, lens.length * 2);
                }
                lens[count++] = n;
            }
            i += 12 + n;
            if ("IEND".equals(type)) {
                return Arrays.copyOf(lens, count);
            }
        }
        throw new IllegalArgumentException("png: missing IEND");
    }

    private static void parseIhdr(RasterImage image, byte[] file, int at, int n) {
        if (n != 13) {
            throw new IllegalArgumentException("png: bad IHDR");
        }
        image.width = int32(file, at);
        image.height = int32(file, at + 4);
        int depth = file[at + 8] & 0xff;
        image.colorType = file[at + 9] & 0xff;
        if (file[at + 10] != 0 || file[at + 11] != 0) {
            throw new IllegalArgumentException("png: unknown compression or filter method");
        }
        if (file[at + 12] != 0) {
            throw new IllegalArgumentException("png: interlaced PNG is not supported");
        }
        if (depth != 8) {
            throw new IllegalArgumentException("png: bit depth " + depth + " is not supported (8-bit only)");
        }
        if (image.spp() == 0) {
            throw new IllegalArgumentException("png: color type " + image.colorType + " is not supported");
        }
        if (image.width <= 0 || image.height <= 0 || image.width > MAX_DIM || image.height > MAX_DIM) {
            throw new IllegalArgumentException("png: unreasonable dimensions");
        }
        if ((long) image.width * image.height > MAX_PIXELS) {
            throw new IllegalArgumentException("png: image exceeds the pixel cap");
        }
    }

    private static byte[] inflate(RasterImage image, byte[] idat) {
        int stride = image.stride();
        int want = image.height * (stride + 1);
        Inflater inflater = new Inflater();
        inflater.setInput(idat);
        byte[] raw = new byte[want];
        try {
            int n = inflater.inflate(raw);
            if (n != want || inflater.getRemaining() != 0 || !inflater.finished()) {
                throw new IllegalArgumentException("png: inflated " + n + " bytes, want " + want);
            }
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("png: inflate failed");
        } finally {
            inflater.end();
        }
        return unfilter(raw, image.height, stride, image.spp());
    }

    private static byte[] unfilter(byte[] raw, int height, int stride, int spp) {
        byte[] out = new byte[height * stride];
        byte[] prev = new byte[stride];
        for (int y = 0; y < height; y++) {
            int ft = raw[y * (stride + 1)] & 0xff;
            int src = y * (stride + 1) + 1;
            int dst = y * stride;
            switch (ft) {
                case 0 -> System.arraycopy(raw, src, out, dst, stride);
                case 1 -> {
                    for (int i = 0; i < stride; i++) {
                        int left = i >= spp ? out[dst + i - spp] & 0xff : 0;
                        out[dst + i] = (byte) ((raw[src + i] & 0xff) + left);
                    }
                }
                case 2 -> {
                    for (int i = 0; i < stride; i++) {
                        out[dst + i] = (byte) ((raw[src + i] & 0xff) + (prev[i] & 0xff));
                    }
                }
                case 3 -> {
                    for (int i = 0; i < stride; i++) {
                        int left = i >= spp ? out[dst + i - spp] & 0xff : 0;
                        out[dst + i] = (byte) ((raw[src + i] & 0xff) + (left + (prev[i] & 0xff)) / 2);
                    }
                }
                case 4 -> {
                    for (int i = 0; i < stride; i++) {
                        int a = 0;
                        int c = 0;
                        if (i >= spp) {
                            a = out[dst + i - spp] & 0xff;
                            c = prev[i - spp] & 0xff;
                        }
                        out[dst + i] = (byte) ((raw[src + i] & 0xff) + paeth(a, prev[i] & 0xff, c));
                    }
                }
                default -> throw new IllegalArgumentException("png: filter type " + ft);
            }
            System.arraycopy(out, dst, prev, 0, stride);
        }
        return out;
    }

    private static byte[] filterAll(byte[] samples, int height, int stride, int spp) {
        byte[] out = new byte[height * (stride + 1)];
        byte[] row = new byte[stride + 1];
        byte[] best = new byte[stride + 1];
        byte[] prev = null;
        for (int y = 0; y < height; y++) {
            int src = y * stride;
            int bestScore = Integer.MAX_VALUE;
            for (int kind = 0; kind <= 4; kind++) {
                filterRow(row, samples, src, prev, stride, spp, kind);
                int score = score(row);
                if (score < bestScore) {
                    bestScore = score;
                    System.arraycopy(row, 0, best, 0, row.length);
                }
            }
            System.arraycopy(best, 0, out, y * (stride + 1), best.length);
            if (prev == null) {
                prev = new byte[stride];
            }
            System.arraycopy(samples, src, prev, 0, stride);
        }
        return out;
    }

    private static void filterRow(byte[] dst, byte[] src, int srcAt, byte[] prev, int stride, int spp, int kind) {
        dst[0] = (byte) kind;
        for (int i = 0; i < stride; i++) {
            int a = 0;
            int b = 0;
            int c = 0;
            if (prev != null) {
                b = prev[i] & 0xff;
            }
            if (i >= spp) {
                a = src[srcAt + i - spp] & 0xff;
                if (prev != null) {
                    c = prev[i - spp] & 0xff;
                }
            }
            int pred = switch (kind) {
                case 1 -> a;
                case 2 -> b;
                case 3 -> (a + b) / 2;
                case 4 -> paeth(a, b, c);
                default -> 0;
            };
            dst[i + 1] = (byte) ((src[srcAt + i] & 0xff) - pred);
        }
    }

    private static int score(byte[] row) {
        int sum = 0;
        for (int i = 1; i < row.length; i++) {
            int v = row[i];
            if (v < 0) {
                v = -v;
            }
            sum += v;
        }
        return sum;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }
        if (pb <= pc) {
            return b;
        }
        return c;
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(6);
        deflater.setInput(input);
        deflater.finish();
        byte[] buf = new byte[input.length + 64 + input.length / 10];
        int off = 0;
        while (!deflater.finished()) {
            if (off == buf.length) {
                buf = Arrays.copyOf(buf, buf.length * 2);
            }
            off += deflater.deflate(buf, off, buf.length - off);
        }
        deflater.end();
        return Arrays.copyOf(buf, off);
    }

    private static boolean keep(String type, boolean metadata) {
        boolean safe = switch (type) {
            case "gAMA", "sRGB", "cHRM", "pHYs", "iCCP", "sBIT" -> true;
            default -> false;
        };
        if (safe) {
            return true;
        }
        if (!metadata) {
            return false;
        }
        return switch (type) {
            case "eXIf", "tEXt", "iTXt", "zTXt", "tIME" -> true;
            default -> false;
        };
    }

    private static int chunk(byte[] out, int p, String type, byte[] data) {
        put32(out, p, data.length);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, out, p + 4, 4);
        System.arraycopy(data, 0, out, p + 8, data.length);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        put32(out, p + 8 + data.length, (int) crc.getValue());
        return p + 12 + data.length;
    }

    private static byte[] concat(byte[] left, byte[] src, int off, int n) {
        byte[] out = Arrays.copyOf(left, left.length + n);
        System.arraycopy(src, off, out, left.length, n);
        return out;
    }

    private static boolean matchesSig(byte[] file) {
        if (file.length < SIG.length) {
            return false;
        }
        for (int i = 0; i < SIG.length; i++) {
            if (file[i] != SIG[i]) {
                return false;
            }
        }
        return true;
    }

    private static int int32(byte[] b, int i) {
        return ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }

    private static void put32(byte[] b, int i, int v) {
        b[i] = (byte) (v >>> 24);
        b[i + 1] = (byte) (v >>> 16);
        b[i + 2] = (byte) (v >>> 8);
        b[i + 3] = (byte) v;
    }
}
