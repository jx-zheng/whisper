package com.jxzheng.whisper.schemes.costs;

/**
 * Uniform embedding revisited distortion for quantized DCT blocks.
 * DC is wet. {@code ρ = q(u,v) * (1/E + 1/Ē)} on AC coefficients.
 */
public final class UerdCost {

    private UerdCost() {
    }

    public static double[] compute(int[] coeffs, int blocksX, int blocksY, int[] quant) {
        int blocks = blocksX * blocksY;
        if (blocksX <= 0 || blocksY <= 0 || coeffs == null || coeffs.length != blocks * 64 || quant == null || quant.length < 64) {
            throw new IllegalArgumentException("uerd: coefficient plane does not match the block grid");
        }
        double[] energy = new double[blocks];
        for (int b = 0; b < blocks; b++) {
            int base = b * 64;
            double sum = 0;
            for (int k = 0; k < 64; k++) {
                int v = coeffs[base + k];
                sum += v < 0 ? -v : v;
            }
            energy[b] = sum < 1 ? 1 : sum;
        }
        double[] avg = box(energy, blocksX, blocksY);
        double[] out = new double[blocks * 64];
        for (int b = 0; b < blocks; b++) {
            double e = energy[b];
            double ea = avg[b] < 1 ? 1 : avg[b];
            int base = b * 64;
            out[base] = Double.POSITIVE_INFINITY;
            for (int k = 1; k < 64; k++) {
                int q = quant[k];
                if (q < 1) {
                    q = 1;
                }
                out[base + k] = q * (1 / e + 1 / ea);
            }
        }
        return out;
    }

    public static float[] floats(int[] coeffs, int blocksX, int blocksY, int[] quant) {
        double[] costs = compute(coeffs, blocksX, blocksY, quant);
        float[] out = new float[costs.length];
        for (int i = 0; i < costs.length; i++) {
            out[i] = (float) costs[i];
        }
        return out;
    }

    private static double[] box(double[] src, int w, int h) {
        double[] dst = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double sum = 0;
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) {
                            continue;
                        }
                        sum += src[yy * w + xx];
                        n++;
                    }
                }
                dst[y * w + x] = sum / n;
            }
        }
        return dst;
    }
}
