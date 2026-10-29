package com.jxzheng.whisper.schemes.costs;

/**
 * HILL distortion (Li, Wang, Huang, Li, ICIP 2014): KB residual, absolute value,
 * 3×3 mean, reciprocal, then a 15×15 mean. Box filters use prefix sums.
 */
public final class HillCost {

    private HillCost() {
    }

    public static double[] compute(byte[] pixels, int width, int height) {
        if (width <= 0 || height <= 0 || pixels == null || pixels.length != width * height) {
            throw new IllegalArgumentException("hill: sample buffer does not match the plane");
        }
        double[] src = new double[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            src[i] = pixels[i] & 0xff;
        }
        double[] residual = kb(src, width, height);
        for (int i = 0; i < residual.length; i++) {
            if (residual[i] < 0) {
                residual[i] = -residual[i];
            }
        }
        double[] smooth = box(residual, width, height, 3);
        double[] rho = new double[pixels.length];
        for (int i = 0; i < smooth.length; i++) {
            rho[i] = 1.0 / (smooth[i] + 1e-6);
        }
        return box(rho, width, height, 15);
    }

    public static float[] floats(byte[] pixels, int width, int height) {
        double[] costs = compute(pixels, width, height);
        float[] out = new float[costs.length];
        for (int i = 0; i < costs.length; i++) {
            out[i] = (float) costs[i];
        }
        return out;
    }

    private static double[] kb(double[] src, int w, int h) {
        double[] k = {-1, 2, -1, 2, -4, 2, -1, 2, -1};
        double[] dst = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double sum = 0;
                int ki = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = clamp(y + dy, h);
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = clamp(x + dx, w);
                        sum += src[yy * w + xx] * k[ki++];
                    }
                }
                dst[y * w + x] = sum;
            }
        }
        return dst;
    }

    static double[] box(double[] src, int w, int h, int k) {
        int r = k / 2;
        double[] tmp = new double[w * h];
        double[] dst = new double[w * h];
        double[] prefix = new double[Math.max(w, h) + 1];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            prefix[0] = 0;
            for (int x = 0; x < w; x++) {
                prefix[x + 1] = prefix[x] + src[row + x];
            }
            double left = src[row];
            double right = src[row + w - 1];
            for (int x = 0; x < w; x++) {
                int from = x - r;
                int to = x + r;
                int extraL = from < 0 ? -from : 0;
                int extraR = to >= w ? to - (w - 1) : 0;
                int cl = Math.max(from, 0);
                int cr = Math.min(to, w - 1);
                double sum = prefix[cr + 1] - prefix[cl] + extraL * left + extraR * right;
                tmp[row + x] = sum / k;
            }
        }
        for (int x = 0; x < w; x++) {
            prefix[0] = 0;
            for (int y = 0; y < h; y++) {
                prefix[y + 1] = prefix[y] + tmp[y * w + x];
            }
            double top = tmp[x];
            double bottom = tmp[(h - 1) * w + x];
            for (int y = 0; y < h; y++) {
                int from = y - r;
                int to = y + r;
                int extraT = from < 0 ? -from : 0;
                int extraB = to >= h ? to - (h - 1) : 0;
                int ct = Math.max(from, 0);
                int cb = Math.min(to, h - 1);
                double sum = prefix[cb + 1] - prefix[ct] + extraT * top + extraB * bottom;
                dst[y * w + x] = sum / k;
            }
        }
        return dst;
    }

    private static int clamp(int v, int n) {
        if (v < 0) {
            return 0;
        }
        if (v >= n) {
            return n - 1;
        }
        return v;
    }
}
