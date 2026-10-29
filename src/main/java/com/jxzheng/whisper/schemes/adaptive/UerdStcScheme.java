package com.jxzheng.whisper.schemes.adaptive;

import java.util.Arrays;
import java.util.Optional;

import com.jxzheng.whisper.carrier.Carrier;
import com.jxzheng.whisper.carrier.CarrierProbe;
import com.jxzheng.whisper.carrier.JpegCoefficients;
import com.jxzheng.whisper.exceptions.MessageTooLongException;
import com.jxzheng.whisper.ops.Progress;
import com.jxzheng.whisper.schemes.Capacity;
import com.jxzheng.whisper.schemes.EmbedOptions;
import com.jxzheng.whisper.schemes.RatePreset;
import com.jxzheng.whisper.schemes.SchemeInfo;
import com.jxzheng.whisper.schemes.StegoScheme;
import com.jxzheng.whisper.schemes.costs.UerdCost;
import com.jxzheng.whisper.schemes.framing.ParityFraming;

/**
 * UERD costs and a binary STC on nonzero luminance AC coefficients.
 * Magnitude 1 is wet, so a change cannot create or destroy a nonzero.
 * The JPEG container is not recompressed: quantisation and Huffman tables stay.
 */
public final class UerdStcScheme implements StegoScheme {

    public static final String ID = "uerd-stc";
    public static final double HARD_RATE = 0.30;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public SchemeInfo info() {
        return new SchemeInfo(ID, "Adaptive (JPEG)", "Keeps your JPEG as JPEG", "bpnzAC");
    }

    @Override
    public boolean accepts(CarrierProbe probe) {
        if (probe == null || probe.prefix() == null || probe.prefix().length < 3) {
            return false;
        }
        byte[] p = probe.prefix();
        if ((p[0] & 0xff) == 0xff && (p[1] & 0xff) == 0xd8 && (p[2] & 0xff) == 0xff) {
            return true;
        }
        String name = probe.name() == null ? "" : probe.name().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    @Override
    public Capacity estimate(Carrier cover, EmbedOptions options) {
        JpegCoefficients jpeg = jpeg(cover);
        int nz = jpeg.nzac();
        double rate = Math.min(options.preset().rate(), HARD_RATE);
        int max = ParityFraming.userBudget(nz, nz, rate);
        int recommended = ParityFraming.userBudget(nz, nz, Math.min(rate, RatePreset.STANDARD.rate()));
        return new Capacity(max, recommended, "bpnzAC", rate);
    }

    @Override
    public Carrier embed(Carrier cover, byte[] payload, byte[] visitSeed, EmbedOptions options, Progress progress) {
        if (progress != null) {
            progress.stage("Embedding");
        }
        JpegCoefficients jpeg = jpeg(cover);
        int nz = jpeg.nzac();
        if (nz == 0) {
            throw new MessageTooLongException("JPEG has no nonzero AC coefficients");
        }
        double rate = Math.min(options.preset().rate(), HARD_RATE);
        double actual = (double) (ParityFraming.PUBLIC_BITS + ParityFraming.HEADER_BITS + payload.length * 8) / nz;
        if (actual > rate + 1e-12) {
            throw new MessageTooLongException(
                    "Payload needs " + actual + " bpnzAC and the cap is " + rate + " (" + nz + " nonzero AC)");
        }
        int[] luma = jpeg.luma().clone();
        float[] rho = UerdCost.floats(luma, jpeg.blocksX(), jpeg.blocksY(), jpeg.lumaQuant());
        Ac ac = ac(luma, rho);
        ParityFraming.Embedded embedded = ParityFraming.embed(
                ac.samples, ac.costs, payload, visitSeed, 10, rate, Integer.MIN_VALUE, Integer.MAX_VALUE, true);
        for (int i = 0; i < ac.back.length; i++) {
            luma[ac.back[i]] = embedded.samples()[i];
        }
        jpeg.setLuma(luma);
        byte[] encoded = jpeg.encode(options.keepMetadata());
        byte[] got = extractEncoded(encoded, visitSeed, rate);
        if (!Arrays.equals(got, payload)) {
            throw new IllegalStateException("uerd-stc self-check failed");
        }
        return Carrier.encoded(encoded, "jpg");
    }

    @Override
    public byte[] extract(Carrier stego, byte[] visitSeed, EmbedOptions options) {
        if (stego.encoded() == null) {
            throw new IllegalArgumentException("uerd-stc needs JPEG bytes");
        }
        double rate = Math.min(options.preset().rate(), HARD_RATE);
        return extractEncoded(stego.encoded(), visitSeed, rate);
    }

    @Override
    public Optional<byte[]> publicSalt(Carrier stego, RatePreset preset) {
        try {
            JpegCoefficients jpeg = JpegCoefficients.parse(stego.encoded());
            Ac ac = ac(jpeg.luma(), null);
            return Optional.of(ParityFraming.readPublic(ac.samples, 10, Math.min(preset.rate(), HARD_RATE)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static byte[] extractEncoded(byte[] encoded, byte[] seed, double rate) {
        JpegCoefficients jpeg = JpegCoefficients.parse(encoded);
        Ac ac = ac(jpeg.luma(), null);
        return ParityFraming.extract(ac.samples, seed, 10, rate);
    }

    public static Ac ac(int[] luma, float[] rho) {
        int count = 0;
        for (int i = 0; i < luma.length; i++) {
            if (i % 64 != 0 && luma[i] != 0) {
                count++;
            }
        }
        int[] samples = new int[count];
        int[] back = new int[count];
        float[] costs = rho == null ? null : new float[count];
        int n = 0;
        for (int i = 0; i < luma.length; i++) {
            if (i % 64 == 0 || luma[i] == 0) {
                continue;
            }
            samples[n] = luma[i];
            back[n] = i;
            if (costs != null) {
                float c = rho[i];
                if (luma[i] == 1 || luma[i] == -1) {
                    c = Float.POSITIVE_INFINITY;
                }
                costs[n] = c;
            }
            n++;
        }
        return new Ac(samples, costs, back);
    }

    private static JpegCoefficients jpeg(Carrier cover) {
        if (cover.encoded() == null) {
            throw new IllegalArgumentException("uerd-stc needs JPEG bytes");
        }
        return JpegCoefficients.parse(cover.encoded());
    }

    public record Ac(int[] samples, float[] costs, int[] back) {
    }
}
