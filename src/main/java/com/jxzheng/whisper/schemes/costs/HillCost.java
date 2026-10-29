package com.jxzheng.whisper.schemes.costs;

/**
 * HILL distortion (Li, Wang, Huang, Li, ICIP 2014): KB residual, absolute value,
 * 3×3 mean, reciprocal, then a 15×15 mean. Box filters use prefix sums and two buffers.
 */
public final class HillCost {

    private HillCost() {
    }

    public static double[] compute(byte[] pixels, int width, int height) {
        if (width <= 0 || height <= 0 || pixels == null || pixels.length != width * height) {
            throw new IllegalArgumentException("hill: sample buffer does not match the plane");
        }
        double[] a = new double[pixels.length];
        double[] b = new double[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            a[i] = pixels[i] & 0xff;
        }
        kb(a, b, width, height);
        for (int i = 0; i < b.length; i++) {
            if (b[i] < 0) {
                b[i] = -b[i];
            }
        }
        // 3×3 mean of |residual| lands in a.
        mean(b, a, width, height, 3);
        for (int i = 0; i < a.length; i++) {
            b[i] = 1.0 / (a[i] + 1e-6);
        }
        // 15×15 mean lands in a.
        mean(b, a, width, height, 15);
        return a;
    }

    public static float[] floats(byte[] pixels, int width, int height) {
        double[] costs = compute(pixels, width, height);
        float[] out = new float[costs.length];
        for (int i = 0; i < costs.length; i++) {
            out[i] = (float) costs[i];
        }
        return out;
    }

    private static void kb(double[] src, double[] dst, int w, int h) {
        double[] kernel = {-1, 2, -1, 2, -4, 2, -1, 2, -1};
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double sum = 0;
                int ki = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = clamp(y + dy, h);
                    for (int dx = -1; dx <= 1; dx++) {
                        sum += src[yy * w + clamp(x + dx, w)] * kernel[ki++];
                    }
                }
                dst[y * w + x] = sum;
            }
        }
    }

    /** Separable replicate-border box mean. Result is written to {@code dst}. {@code src} is scratch. */
    private static void mean(double[] src, double[] dst, int w, int h, int k) {
        horizontal(src, dst, w, h, k);
        vertical(dst, src, w, h, k);
        System.arraycopy(src, 0, dst, 0, src.length);
    }

    private static void horizontal(double[] src, double[] dst, int w, int h, int k) {
        int radius = k / 2;
        double[] prefix = new double[w + 1];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            prefix[0] = 0;
            for (int x = 0; x < w; x++) {
                prefix[x + 1] = prefix[x] + src[row + x];
            }
            double left = src[row];
            double right = src[row + w - 1];
            for (int x = 0; x < w; x++) {
                dst[row + x] = window(prefix, left, right, x, radius, w) / k;
            }
        }
    }

    private static void vertical(double[] src, double[] dst, int w, int h, int k) {
        int radius = k / 2;
        double[] prefix = new double[h + 1];
        for (int x = 0; x < w; x++) {
            prefix[0] = 0;
            for (int y = 0; y < h; y++) {
                prefix[y + 1] = prefix[y] + src[y * w + x];
            }
            double top = src[x];
            double bottom = src[(h - 1) * w + x];
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = window(prefix, top, bottom, y, radius, h) / k;
            }
        }
    }

    private static double window(double[] prefix, double edgeStart, double edgeEnd, int at, int radius, int n) {
        int from = at - radius;
        int to = at + radius;
        int extraStart = from < 0 ? -from : 0;
        int extraEnd = to >= n ? to - (n - 1) : 0;
        int start = Math.max(from, 0);
        int end = Math.min(to, n - 1);
        return prefix[end + 1] - prefix[start] + extraStart * edgeStart + extraEnd * edgeEnd;
    }

    private static int clamp(int value, int limit) {
        if (value < 0) {
            return 0;
        }
        if (value >= limit) {
            return limit - 1;
        }
        return value;
    }
}
