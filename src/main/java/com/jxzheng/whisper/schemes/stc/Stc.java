package com.jxzheng.whisper.schemes.stc;

/**
 * Clean-room binary syndrome-trellis code (Filler, Judas, Fridrich, TIFS 2011).
 * The parity-check submatrix and the segmented Viterbi search follow that paper
 * the same way Whisper2's own clean-room code does. This is not the Binghamton toolbox.
 */
public final class Stc {

    public static final int DEFAULT_HEIGHT = 10;
    static final int SEGMENT = 8192;

    private Stc() {
    }

    /**
     * Embeds {@code message} into LSB cover bits. {@code costs[i]} is the price of
     * flipping {@code cover[i]}; {@link Float#POSITIVE_INFINITY} is wet.
     *
     * @return how many cover bits changed
     */
    public static int embed(byte[] cover, float[] costs, byte[] message, int h, byte[] out) {
        if (h == 0) {
            h = DEFAULT_HEIGHT;
        }
        if (h < 1 || h > 12) {
            throw new IllegalArgumentException("stc height out of range");
        }
        if (cover == null || costs == null || cover.length != costs.length) {
            throw new IllegalArgumentException("stc cover and cost lengths differ");
        }
        if (out == null || out.length != cover.length) {
            throw new IllegalArgumentException("stc output length");
        }
        if (message == null || message.length == 0) {
            System.arraycopy(cover, 0, out, 0, cover.length);
            return 0;
        }
        for (byte bit : cover) {
            if ((bit & 0xff) > 1) {
                throw new IllegalArgumentException("stc cover bit is not 0 or 1");
            }
        }
        for (float cost : costs) {
            if (Float.isNaN(cost) || cost < 0) {
                throw new IllegalArgumentException("stc costs must be non-negative or +Inf");
            }
        }
        int k = message.length * 8;
        if (cover.length < k) {
            throw new IllegalArgumentException("stc cover is shorter than the message");
        }
        int width = cover.length / k;
        int used = k * width;
        int[] cols = submatrix(h, width);
        byte[] bits = unpack(message);
        int changes = viterbi(cover, costs, bits, h, cols, used, out);
        if (used < cover.length) {
            System.arraycopy(cover, used, out, used, cover.length - used);
        }
        return changes;
    }

    public static byte[] extract(byte[] stego, int messageBytes, int h) {
        if (h == 0) {
            h = DEFAULT_HEIGHT;
        }
        if (h < 1 || h > 12) {
            throw new IllegalArgumentException("stc height out of range");
        }
        if (messageBytes < 0) {
            throw new IllegalArgumentException("stc negative message length");
        }
        if (messageBytes == 0) {
            return new byte[0];
        }
        int k = messageBytes * 8;
        if (stego == null || stego.length < k) {
            throw new IllegalArgumentException("stc cover is shorter than the message");
        }
        int width = stego.length / k;
        int used = k * width;
        int[] cols = submatrix(h, width);
        byte[] bits = new byte[k];
        int state = 0;
        for (int i = 0; i < used; i++) {
            int y = stego[i] & 0xff;
            if (y > 1) {
                throw new IllegalArgumentException("stc stego bit is not 0 or 1");
            }
            if (y == 1) {
                state ^= cols[i % width];
            }
            if (i % width == width - 1) {
                bits[i / width] = (byte) (state & 1);
                state >>>= 1;
            }
        }
        return pack(bits);
    }

    static int[] submatrix(int h, int w) {
        int[] cols = new int[w];
        long s = 0x9E3779B97F4A7C15L;
        s ^= (long) h * 0xBF58476D1CE4E5B9L;
        s ^= (long) w * 0x94D049BB133111EBL;
        for (int j = 0; j < w; j++) {
            int mask = 1;
            for (int r = 1; r < h - 1; r++) {
                s ^= s >>> 12;
                s ^= s << 25;
                s ^= s >>> 27;
                long mixed = s * 0x2545F4914F6CDD1DL;
                if ((mixed & 1L) == 1L) {
                    mask |= 1 << r;
                }
            }
            if (h > 1) {
                mask |= 1 << (h - 1);
            }
            cols[j] = mask;
        }
        return cols;
    }

    private static int viterbi(byte[] cover, float[] costs, byte[] msg, int h, int[] cols, int n, byte[] out) {
        int states = 1 << h;
        int seg = Math.min(SEGMENT, Math.max(n, 1));
        int nSeg = (n + seg - 1) / seg;
        double[][] checkpoints = new double[nSeg + 1][];
        double[] init = new double[states];
        for (int i = 1; i < states; i++) {
            init[i] = Double.POSITIVE_INFINITY;
        }
        checkpoints[0] = init;
        for (int s = 0; s < nSeg; s++) {
            int start = s * seg;
            int end = Math.min(start + seg, n);
            checkpoints[s + 1] = runSegment(checkpoints[s], cover, costs, msg, cols, h, start, end, null);
        }
        int bestS = -1;
        double best = Double.POSITIVE_INFINITY;
        double[] last = checkpoints[nSeg];
        for (int s = 0; s < states; s++) {
            if (last[s] < best) {
                best = last[s];
                bestS = s;
            }
        }
        if (bestS < 0 || Double.isInfinite(best)) {
            throw new IllegalArgumentException("stc: no embedding path");
        }
        Backpointer bp = new Backpointer(seg, states);
        int state = bestS;
        int width = cols.length;
        for (int sidx = nSeg - 1; sidx >= 0; sidx--) {
            int start = sidx * seg;
            int end = Math.min(start + seg, n);
            java.util.Arrays.fill(bp.words, 0L);
            runSegment(checkpoints[sidx], cover, costs, msg, cols, h, start, end, bp);
            for (int i = end - 1; i >= start; i--) {
                int y = bp.get(i - start, state);
                out[i] = (byte) y;
                int mask = cols[i % width];
                int tmp = state;
                if (i % width == width - 1) {
                    tmp = (state << 1) | (msg[i / width] & 1);
                }
                if (y == 1) {
                    tmp ^= mask;
                }
                state = tmp;
            }
        }
        if (state != 0) {
            throw new IllegalStateException("stc backtrack missed the zero state");
        }
        int changes = 0;
        for (int i = 0; i < n; i++) {
            if (out[i] != cover[i]) {
                changes++;
            }
        }
        return changes;
    }

    private static double[] runSegment(
            double[] startCosts, byte[] cover, float[] costs, byte[] msg, int[] cols,
            int h, int start, int end, Backpointer bp) {
        int states = 1 << h;
        int width = cols.length;
        double[] prev = startCosts.clone();
        double[] curr = new double[states];
        for (int i = start; i < end; i++) {
            int mask = cols[i % width];
            int x = cover[i] & 0xff;
            double rho = costs[i];
            boolean wet = Double.isInfinite(rho);
            int layer = i - start;
            if (i % width != width - 1) {
                stepFree(prev, curr, bp, layer, mask, x, rho);
            } else {
                java.util.Arrays.fill(curr, Double.POSITIVE_INFINITY);
                stepConstrained(prev, curr, bp, layer, mask, x, rho, wet, msg[i / width]);
            }
            double[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev;
    }

    private static void stepFree(double[] prev, double[] curr, Backpointer bp, int layer, int mask, int x, double rho) {
        int states = prev.length;
        double add0 = x == 0 ? 0.0 : rho;
        double add1 = x == 0 ? rho : 0.0;
        // Mask bit 0 is always set, so each pair (s, s^mask) is one even index and one odd index.
        if (bp == null) {
            for (int s = 0; s < states; s += 2) {
                int t = s ^ mask;
                double ps = prev[s];
                double pt = prev[t];
                double stayS = ps + add0;
                double flipS = pt + add1;
                double stayT = pt + add0;
                double flipT = ps + add1;
                curr[s] = flipS < stayS ? flipS : stayS;
                curr[t] = flipT < stayT ? flipT : stayT;
            }
            return;
        }
        for (int s = 0; s < states; s += 2) {
            int t = s ^ mask;
            double ps = prev[s];
            double pt = prev[t];
            double stayS = ps + add0;
            double flipS = pt + add1;
            double stayT = pt + add0;
            double flipT = ps + add1;
            if (flipS < stayS) {
                curr[s] = flipS;
                bp.put(layer, s, 1);
            } else {
                curr[s] = stayS;
            }
            if (flipT < stayT) {
                curr[t] = flipT;
                bp.put(layer, t, 1);
            } else {
                curr[t] = stayT;
            }
        }
    }

    private static void stepConstrained(
            double[] prev, double[] curr, Backpointer bp, int layer, int mask, int x, double rho, boolean wet, byte mbit) {
        int states = prev.length;
        int message = mbit & 1;
        // Column masks always have the first row set, so exactly one y per state
        // shifts out the message bit. Ties keep the lower state index.
        if (!wet && bp == null) {
            for (int s = 0; s < states; s++) {
                int y = (s & 1) ^ message;
                double cost = prev[s] + (y ^ x) * rho;
                int ns = (s ^ (mask & -y)) >>> 1;
                if (cost < curr[ns]) {
                    curr[ns] = cost;
                }
            }
            return;
        }
        for (int s = 0; s < states; s++) {
            double c = prev[s];
            int y = (s & 1) ^ message;
            boolean changed = y != x;
            if (changed && wet) {
                continue;
            }
            double cost = changed ? c + rho : c;
            int ns = (s ^ (mask & -y)) >>> 1;
            if (cost < curr[ns]) {
                curr[ns] = cost;
                if (bp != null) {
                    bp.put(layer, ns, y);
                }
            }
        }
    }

    static byte[] unpack(byte[] message) {
        byte[] out = new byte[message.length * 8];
        for (int i = 0; i < message.length; i++) {
            int b = message[i] & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                out[i * 8 + bit] = (byte) ((b >>> (7 - bit)) & 1);
            }
        }
        return out;
    }

    static byte[] pack(byte[] bits) {
        byte[] out = new byte[bits.length / 8];
        for (int i = 0; i < out.length; i++) {
            int b = 0;
            for (int bit = 0; bit < 8; bit++) {
                b = (b << 1) | (bits[i * 8 + bit] & 1);
            }
            out[i] = (byte) b;
        }
        return out;
    }

    /** Bit-packed y choices. One segment, reused across the backward pass. */
    static final class Backpointer {
        final int states;
        final long[] words;

        Backpointer(int layers, int states) {
            this.states = states;
            this.words = new long[((layers * states) + 63) / 64];
        }

        void put(int layer, int state, int y) {
            int idx = layer * states + state;
            long bit = 1L << (idx & 63);
            int word = idx >>> 6;
            if (y == 1) {
                words[word] |= bit;
            } else {
                words[word] &= ~bit;
            }
        }

        int get(int layer, int state) {
            int idx = layer * states + state;
            return (words[idx >>> 6] & (1L << (idx & 63))) != 0 ? 1 : 0;
        }
    }
}
