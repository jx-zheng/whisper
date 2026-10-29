package com.jxzheng.whisper.schemes.adaptive;

import java.util.Arrays;

import com.jxzheng.whisper.carrier.BmpCodec;
import com.jxzheng.whisper.carrier.Carrier;
import com.jxzheng.whisper.carrier.PngCodec;
import com.jxzheng.whisper.carrier.RasterImage;
import com.jxzheng.whisper.exceptions.MessageTooLongException;
import com.jxzheng.whisper.ops.Progress;
import com.jxzheng.whisper.schemes.EmbedOptions;
import com.jxzheng.whisper.schemes.StegoScheme;
import com.jxzheng.whisper.schemes.costs.HillCost;
import com.jxzheng.whisper.schemes.framing.ParityFraming;

/**
 * HILL costs and a binary STC on the green (or grey) plane. Alpha is never modified.
 * PNG and 24/32-bit BMP covers are accepted. The stego file is PNG unless the
 * caller asks for BMP.
 */
public final class HillStcScheme implements StegoScheme {

    public static final String ID = "hill-stc";
    public static final double HARD_RATE = 0.20;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Carrier embed(Carrier cover, byte[] payload, byte[] visitSeed, EmbedOptions options, Progress progress) {
        if (progress != null) {
            progress.stage("Embedding");
        }
        RasterImage image = copy(raster(cover));
        byte[] pixels = greenBytes(image);
        float[] costs = HillCost.floats(pixels, image.width, image.height);
        wetTransparency(image, costs);
        int[] samples = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            samples[i] = pixels[i] & 0xff;
        }
        double rate = Math.min(options.preset().rate(), HARD_RATE);
        int pixelCount = image.width * image.height;
        int budget = ParityFraming.userBudget(samples.length, pixelCount, rate);
        if (payload.length > budget + 58) {
            throw new MessageTooLongException(
                    "Envelope is " + payload.length + " bytes but this cover holds " + (budget + 58)
                            + " bytes at this preset (about " + budget + " bytes of content).");
        }
        ParityFraming.Embedded embedded = ParityFraming.embed(
                samples, costs, payload, visitSeed, 10, rate, 0, 255, false);
        writePlane(image, embedded.samples());
        pixels = null;
        samples = null;
        costs = null;
        embedded = null;
        boolean bmp = "bmp".equalsIgnoreCase(options.outputFormat());
        byte[] encoded = bmp ? BmpCodec.write(image) : PngCodec.encode(image, options.keepMetadata());
        byte[] check = bmp
                ? ParityFraming.extract(plane(BmpCodec.read(encoded)).samples, visitSeed, 10, rate)
                : ParityFraming.extract(plane(PngCodec.decode(encoded)).samples, visitSeed, 10, rate);
        if (!Arrays.equals(check, payload)) {
            throw new IllegalStateException("hill-stc self-check failed");
        }
        return Carrier.raster(image, encoded, bmp ? "bmp" : "png");
    }

    @Override
    public byte[] extract(Carrier stego, byte[] visitSeed, EmbedOptions options) {
        double rate = Math.min(options.preset().rate(), HARD_RATE);
        return ParityFraming.extract(plane(raster(stego)).samples, visitSeed, 10, rate);
    }

    public static RasterImage raster(Carrier carrier) {
        if (carrier.raster() != null) {
            return carrier.raster();
        }
        throw new IllegalArgumentException("hill-stc needs a decoded PNG or BMP raster");
    }

    private static byte[] greenBytes(RasterImage image) {
        int spp = image.spp();
        if (spp == 0) {
            throw new IllegalArgumentException("png: bad color type");
        }
        int off = spp >= 3 ? 1 : 0;
        int n = image.width * image.height;
        byte[] pixels = new byte[n];
        for (int i = 0; i < n; i++) {
            pixels[i] = image.samples[i * spp + off];
        }
        return pixels;
    }

    public static Plane plane(RasterImage image) {
        int spp = image.spp();
        if (spp == 0) {
            throw new IllegalArgumentException("png: bad color type");
        }
        int off = spp >= 3 ? 1 : 0;
        int n = image.width * image.height;
        byte[] pixels = new byte[n];
        int[] samples = new int[n];
        for (int i = 0; i < n; i++) {
            int v = image.samples[i * spp + off] & 0xff;
            pixels[i] = (byte) v;
            samples[i] = v;
        }
        return new Plane(pixels, samples);
    }

    private static void writePlane(RasterImage image, int[] samples) {
        int spp = image.spp();
        int off = spp >= 3 ? 1 : 0;
        int n = image.width * image.height;
        if (samples.length != n) {
            throw new IllegalStateException("sample count mismatch");
        }
        for (int i = 0; i < n; i++) {
            int v = samples[i];
            if (v < 0 || v > 255) {
                throw new IllegalStateException("sample out of range");
            }
            image.samples[i * spp + off] = (byte) v;
        }
    }

    /** A ±1 change must not cross a tRNS colour key, or the pixel flips between transparent and opaque. */
    private static void wetTransparency(RasterImage image, float[] costs) {
        int[] key = transparencyKey(image);
        if (key == null) {
            return;
        }
        int spp = image.spp();
        int n = image.width * image.height;
        for (int i = 0; i < n; i++) {
            if (nearKey(image.samples, i * spp, key)) {
                costs[i] = Float.POSITIVE_INFINITY;
            }
        }
    }

    private static int[] transparencyKey(RasterImage image) {
        byte[] data = null;
        for (RasterImage.Chunk chunk : image.ancillary) {
            if ("tRNS".equals(chunk.type())) {
                data = chunk.data();
            }
        }
        if (data == null) {
            return null;
        }
        if (image.colorType == 0 && data.length >= 2) {
            return new int[] {trnsSample(data, 0)};
        }
        if (image.colorType == 2 && data.length >= 6) {
            return new int[] {trnsSample(data, 0), trnsSample(data, 2), trnsSample(data, 4)};
        }
        return null;
    }

    private static int trnsSample(byte[] data, int at) {
        int raw = ((data[at] & 0xff) << 8) | (data[at + 1] & 0xff);
        return raw > 255 ? (raw >> 8) : raw;
    }

    private static boolean nearKey(byte[] samples, int at, int[] key) {
        if (key.length == 1) {
            return Math.abs((samples[at] & 0xff) - key[0]) <= 1;
        }
        int red = samples[at] & 0xff;
        int green = samples[at + 1] & 0xff;
        int blue = samples[at + 2] & 0xff;
        return Math.abs(red - key[0]) <= 1 && Math.abs(green - key[1]) <= 1 && Math.abs(blue - key[2]) <= 1;
    }

    private static RasterImage copy(RasterImage image) {
        RasterImage out = new RasterImage();
        out.width = image.width;
        out.height = image.height;
        out.colorType = image.colorType;
        out.samples = image.samples.clone();
        out.ancillary.addAll(image.ancillary);
        return out;
    }

    public record Plane(byte[] pixels, int[] samples) {
    }
}
