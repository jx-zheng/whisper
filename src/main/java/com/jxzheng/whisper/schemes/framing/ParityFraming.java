package com.jxzheng.whisper.schemes.framing;

import java.util.Arrays;

import com.jxzheng.whisper.exceptions.MessageTooLongException;
import com.jxzheng.whisper.schemes.permute.KeyedPermutation;
import com.jxzheng.whisper.schemes.stc.Stc;

/**
 * Public salt on a fixed spread, then a keyed length prefix and payload.
 * Hide checks that both can be read back before it returns.
 */
public final class ParityFraming {

    public static final int PUBLIC_BYTES = 16;
    public static final int PUBLIC_BITS = 128;
    public static final int HEADER_BYTES = 4;
    public static final int HEADER_BITS = 32;
    public static final int HEADER_WIDTH = 8;
    public static final int HEADER_SPAN = HEADER_BITS * HEADER_WIDTH;
    public static final int MAX_EMBEDDED = 64 << 20;

    /** Not a secret. Spreads the salt so reveal can read it before the visit order. */
    public static final byte[] PUBLIC_SPREAD_SEED = {
            0x6a, 0x1f, (byte) 0x9c, 0x33, (byte) 0x80, (byte) 0xe4, 0x52, 0x0b,
            (byte) 0xc7, 0x15, (byte) 0xaa, 0x48, (byte) 0x91, 0x0d, 0x66, (byte) 0xf2,
            0x3e, 0x77, 0x04, (byte) 0xb8, 0x5d, 0x21, (byte) 0xce, (byte) 0x90,
            0x4a, (byte) 0xd3, 0x18, 0x6f, (byte) 0xe1, 0x57, (byte) 0xab, 0x02
    };

    private ParityFraming() {
    }

    public record Layout(int width, int[] saltIndex, int[] restIndex) {
    }

    public record Embedded(int[] samples, int changes) {
    }

    public static int publicWidth(int n, double cap) {
        cap = cap <= 0 || Double.isNaN(cap) || Double.isInfinite(cap) ? 0.10 : cap;
        int w = (int) Math.ceil(1.0 / cap);
        if (w < 1) {
            w = 1;
        }
        int maxW = n > HEADER_SPAN ? (n - HEADER_SPAN) / PUBLIC_BITS : 0;
        if (maxW < w) {
            throw new MessageTooLongException(
                    "Cover cannot hold a salt at rate " + cap + " (needs " + (PUBLIC_BITS * w + HEADER_SPAN) + " samples)");
        }
        return w;
    }

    public static Layout layout(int n, double cap) {
        int width = publicWidth(n, cap);
        int[] order = KeyedPermutation.order(n, PUBLIC_SPREAD_SEED);
        int saltN = PUBLIC_BITS * width;
        return new Layout(width, Arrays.copyOf(order, saltN), Arrays.copyOfRange(order, saltN, order.length));
    }

    public static byte[] readPublic(int[] samples, int height, double rateCap) {
        Layout layout = layout(samples.length, rateCap);
        int[] gathered = gather(samples, layout.saltIndex());
        return extractFixed(gathered, height);
    }

    public static Embedded embed(
            int[] samples, float[] costs, byte[] payload, byte[] seed,
            int height, double rateCap, int min, int max, boolean shrink) {
        if (samples.length != costs.length) {
            throw new IllegalArgumentException("sample and cost lengths differ");
        }
        if (seed == null || seed.length != 32) {
            throw new IllegalArgumentException("stego seed must be 32 bytes");
        }
        if (payload.length > MAX_EMBEDDED) {
            throw new MessageTooLongException("Envelope exceeds " + MAX_EMBEDDED + " bytes");
        }
        Layout layout = layout(samples.length, rateCap);
        byte[] pub = publicMaterial(payload);
        int[] saltSamples = gather(samples, layout.saltIndex());
        float[] saltCosts = gatherFloat(costs, layout.saltIndex());
        int[] codedSalt = embedFixed(saltSamples, saltCosts, pub, height, min, max, shrink);
        int[] restSamples = gather(samples, layout.restIndex());
        float[] restCosts = gatherFloat(costs, layout.restIndex());
        int[] codedRest = embedPermuted(restSamples, restCosts, payload, seed, height, min, max, shrink);
        int[] out = samples.clone();
        scatter(out, layout.saltIndex(), codedSalt);
        scatter(out, layout.restIndex(), codedRest);
        byte[] gotPub = readPublic(out, height, rateCap);
        if (!Arrays.equals(gotPub, pub)) {
            throw new IllegalStateException("embed self-check failed on the public salt");
        }
        byte[] got = extract(out, seed, height, rateCap);
        if (!Arrays.equals(got, payload)) {
            throw new IllegalStateException("embed self-check failed");
        }
        int changes = 0;
        for (int i = 0; i < samples.length; i++) {
            if (out[i] != samples[i]) {
                changes++;
            }
        }
        return new Embedded(out, changes);
    }

    public static byte[] extract(int[] samples, byte[] seed, int height, double rateCap) {
        if (seed == null || seed.length != 32) {
            throw new IllegalArgumentException("stego seed must be 32 bytes");
        }
        Layout layout = layout(samples.length, rateCap);
        return extractPermuted(gather(samples, layout.restIndex()), seed, height);
    }

    public static int userBudget(int nSamples, int units, double rate) {
        int w = (int) Math.ceil(1.0 / rate);
        if (w < 1) {
            w = 1;
        }
        int saltSpan = PUBLIC_BITS * w;
        if (nSamples < saltSpan + HEADER_SPAN || units <= 0 || rate <= 0) {
            return 0;
        }
        int bits = (int) Math.floor(rate * units) - PUBLIC_BITS - HEADER_BITS;
        if (bits < 0) {
            bits = 0;
        }
        int maxBits = nSamples - saltSpan - HEADER_SPAN;
        if (bits > maxBits) {
            bits = maxBits;
        }
        int embedded = bits / 8;
        int user = embedded - 58;
        if (user > 64 * 1024) {
            int chunks = (user + 65535) / 65536;
            if (chunks < 1) {
                chunks = 1;
            }
            user = embedded - 42 - 16 * chunks;
        }
        return Math.max(user, 0);
    }

    static byte[] publicMaterial(byte[] payload) {
        byte[] out = new byte[PUBLIC_BYTES];
        if (payload.length >= 18) {
            System.arraycopy(payload, 2, out, 0, PUBLIC_BYTES);
        } else {
            System.arraycopy(payload, 0, out, 0, payload.length);
        }
        return out;
    }

    private static int[] embedFixed(int[] samples, float[] costs, byte[] message, int height, int min, int max, boolean shrink) {
        byte[] bits = parities(samples);
        byte[] coded = new byte[bits.length];
        Stc.embed(bits, costs, message, height, coded);
        return apply(samples, bits, coded, min, max, shrink);
    }

    private static byte[] extractFixed(int[] samples, int height) {
        return Stc.extract(parities(samples), PUBLIC_BYTES, height);
    }

    private static int[] embedPermuted(
            int[] samples, float[] costs, byte[] payload, byte[] seed, int height, int min, int max, boolean shrink) {
        int[] perm = KeyedPermutation.order(samples.length, seed);
        byte[] bits = new byte[samples.length];
        float[] pc = new float[samples.length];
        for (int j = 0; j < perm.length; j++) {
            bits[j] = (byte) (samples[perm[j]] & 1);
            pc[j] = costs[perm[j]];
        }
        byte[] coded = codePayload(bits, pc, payload, height);
        int[] out = samples.clone();
        for (int j = 0; j < perm.length; j++) {
            if (coded[j] == bits[j]) {
                continue;
            }
            out[perm[j]] = flip(samples[perm[j]], coded[j], min, max, shrink);
        }
        return out;
    }

    private static byte[] extractPermuted(int[] samples, byte[] seed, int height) {
        int[] perm = KeyedPermutation.order(samples.length, seed);
        byte[] bits = new byte[samples.length];
        for (int j = 0; j < perm.length; j++) {
            bits[j] = (byte) (samples[perm[j]] & 1);
        }
        return decodePayload(bits, height);
    }

    static byte[] codePayload(byte[] bits, float[] costs, byte[] payload, int height) {
        if (bits.length < HEADER_SPAN) {
            throw new MessageTooLongException("Need at least " + HEADER_SPAN + " samples");
        }
        byte[] header = new byte[HEADER_BYTES];
        int n = payload.length;
        header[0] = (byte) (n >>> 24);
        header[1] = (byte) (n >>> 16);
        header[2] = (byte) (n >>> 8);
        header[3] = (byte) n;
        byte[] out = bits.clone();
        byte[] codedHeader = new byte[HEADER_SPAN];
        Stc.embed(Arrays.copyOf(bits, HEADER_SPAN), Arrays.copyOf(costs, HEADER_SPAN), header, height, codedHeader);
        System.arraycopy(codedHeader, 0, out, 0, HEADER_SPAN);
        if (payload.length == 0) {
            return out;
        }
        int k = payload.length * 8;
        int rest = bits.length - HEADER_SPAN;
        if (rest < k) {
            throw new MessageTooLongException("Payload needs " + k + " bits but " + rest + " remain");
        }
        int width = rest / k;
        int used = k * width;
        byte[] coded = new byte[used];
        Stc.embed(
                Arrays.copyOfRange(bits, HEADER_SPAN, HEADER_SPAN + used),
                Arrays.copyOfRange(costs, HEADER_SPAN, HEADER_SPAN + used),
                payload, height, coded);
        System.arraycopy(coded, 0, out, HEADER_SPAN, used);
        return out;
    }

    static byte[] decodePayload(byte[] bits, int height) {
        if (bits.length < HEADER_SPAN) {
            throw new MessageTooLongException("Need at least " + HEADER_SPAN + " samples");
        }
        byte[] header = Stc.extract(Arrays.copyOf(bits, HEADER_SPAN), HEADER_BYTES, height);
        int n = ((header[0] & 0xff) << 24) | ((header[1] & 0xff) << 16) | ((header[2] & 0xff) << 8) | (header[3] & 0xff);
        if (n < 0 || n > MAX_EMBEDDED) {
            throw new MessageTooLongException("Claimed envelope length " + (n & 0xffffffffL));
        }
        if (n == 0) {
            return new byte[0];
        }
        int k = n * 8;
        int rest = bits.length - HEADER_SPAN;
        if (rest < k) {
            throw new MessageTooLongException("Claimed payload does not fit");
        }
        int used = k * (rest / k);
        return Stc.extract(Arrays.copyOfRange(bits, HEADER_SPAN, HEADER_SPAN + used), n, height);
    }

    private static int[] apply(int[] samples, byte[] before, byte[] after, int min, int max, boolean shrink) {
        int[] out = samples.clone();
        for (int i = 0; i < samples.length; i++) {
            if (after[i] != before[i]) {
                out[i] = flip(samples[i], after[i], min, max, shrink);
            }
        }
        return out;
    }

    static int flip(int value, int want, int min, int max, boolean shrink) {
        if ((value & 1) == (want & 1)) {
            return value;
        }
        boolean plus = value < max;
        boolean minus = value > min;
        if (shrink) {
            if (value > 0 && minus) {
                return value - 1;
            }
            if (value < 0 && plus) {
                return value + 1;
            }
            if (value == 0 && plus) {
                return 1;
            }
        }
        if (plus) {
            return value + 1;
        }
        if (minus) {
            return value - 1;
        }
        throw new IllegalArgumentException("sample cannot move by 1");
    }

    private static byte[] parities(int[] samples) {
        byte[] bits = new byte[samples.length];
        for (int i = 0; i < samples.length; i++) {
            bits[i] = (byte) (samples[i] & 1);
        }
        return bits;
    }

    private static int[] gather(int[] src, int[] index) {
        int[] out = new int[index.length];
        for (int i = 0; i < index.length; i++) {
            out[i] = src[index[i]];
        }
        return out;
    }

    private static float[] gatherFloat(float[] src, int[] index) {
        float[] out = new float[index.length];
        for (int i = 0; i < index.length; i++) {
            out[i] = src[index[i]];
        }
        return out;
    }

    private static void scatter(int[] dst, int[] index, int[] values) {
        for (int i = 0; i < index.length; i++) {
            dst[index[i]] = values[i];
        }
    }
}
