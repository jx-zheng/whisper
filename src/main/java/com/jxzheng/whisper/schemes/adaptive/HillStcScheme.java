package com.jxzheng.whisper.schemes.adaptive;

import java.util.Arrays;
import java.util.Optional;

import com.jxzheng.whisper.carrier.BmpCodec;
import com.jxzheng.whisper.carrier.Carrier;
import com.jxzheng.whisper.carrier.CarrierProbe;
import com.jxzheng.whisper.carrier.PngCodec;
import com.jxzheng.whisper.carrier.RasterImage;
import com.jxzheng.whisper.ops.Progress;
import com.jxzheng.whisper.schemes.Capacity;
import com.jxzheng.whisper.schemes.EmbedOptions;
import com.jxzheng.whisper.schemes.RatePreset;
import com.jxzheng.whisper.schemes.SchemeInfo;
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
    public SchemeInfo info() {
        return new SchemeInfo(ID, "Adaptive (HILL)", "Recommended for PNG", "bpp");
    }

    @Override
    public boolean accepts(CarrierProbe probe) {
        if (probe == null || probe.prefix() == null) {
            return false;
        }
        byte[] p = probe.prefix();
        if (p.length >= 8 && p[0] == (byte) 0x89 && p[1] == 'P' && p[2] == 'N' && p[3] == 'G') {
            return true;
        }
        if (p.length >= 2 && p[0] == 'B' && p[1] == 'M') {
            return true;
        }
        String name = probe.name() == null ? "" : probe.name().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".bmp");
    }

    @Override
    public Capacity estimate(Carrier cover, EmbedOptions options) {
        RasterImage image = raster(cover);
        int pixels = image.width * image.height;
        double rate = options.preset().rate();
        int max = ParityFraming.userBudget(pixels, pixels, Math.min(rate, HARD_RATE));
        int recommended = ParityFraming.userBudget(pixels, pixels, Math.min(rate, RatePreset.STANDARD.rate()));
        return new Capacity(max, recommended, "bpp", rate);
    }

    @Override
    public Carrier embed(Carrier cover, byte[] payload, byte[] visitSeed, EmbedOptions options, Progress progress) {
        if (progress != null) {
            progress.stage("Embedding");
        }
        RasterImage image = copy(raster(cover));
        Plane plane = plane(image);
        float[] costs = HillCost.floats(plane.pixels, image.width, image.height);
        double rate = Math.min(options.preset().rate(), HARD_RATE);
        ParityFraming.Embedded embedded = ParityFraming.embed(
                plane.samples, costs, payload, visitSeed, 10, rate, 0, 255, false);
        writePlane(image, embedded.samples());
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

    @Override
    public Optional<byte[]> publicSalt(Carrier stego, RatePreset preset) {
        try {
            double rate = Math.min(preset.rate(), HARD_RATE);
            return Optional.of(ParityFraming.readPublic(plane(raster(stego)).samples, 10, rate));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static RasterImage raster(Carrier carrier) {
        if (carrier.raster() != null) {
            return carrier.raster();
        }
        throw new IllegalArgumentException("hill-stc needs a decoded PNG or BMP raster");
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
