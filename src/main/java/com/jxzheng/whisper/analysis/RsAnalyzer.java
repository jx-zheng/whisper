package com.jxzheng.whisper.analysis;

import java.awt.image.BufferedImage;

/**
 * Fridrich RS (Regular/Singular) steganalysis.
 *
 * <p>Estimates classic LSB embedding rate from non-overlapping 4-pixel groups
 * using discrimination {@code Σ|xᵢ₊₁-xᵢ|}, flip operator {@code F₁} (LSB flip)
 * on mask M, and dual {@code F₋₁} on the same support.
 */
public final class RsAnalyzer {

    /** Support mask M = [0,1,1,0] — F₁ / F₋₁ applied where the entry is 1. */
    private static final int[] MASK_M = {0, 1, 1, 0};

    public record Result(
            double estimatedRate,
            double regularM,
            double singularM,
            double regularMinusM,
            double singularMinusM) {
        /** Simple RS asymmetry; tends to grow under classic LSB embedding. */
        public double maskAsymmetry() {
            return Math.abs(regularM - regularMinusM) + Math.abs(singularM - singularMinusM);
        }
    }

    public Result analyze(BufferedImage image) {
        Stats original = analyzeImage(image);
        Stats flipped = analyzeImage(flipAllLsbs(image));

        // Paper: d0=R_M-S_M, dMinus0=R_{-M}-S_{-M} on the image;
        // d1 / dMinus1 are the same quantities on the all-LSB-flipped image.
        double d0 = original.rm - original.sm;
        double dMinus0 = original.rMinus - original.sMinus;
        double d1 = flipped.rm - flipped.sm;
        double dMinus1 = flipped.rMinus - flipped.sMinus;

        double rate = solveRate(d0, dMinus0, d1, dMinus1);
        return new Result(rate, original.rm, original.sm, original.rMinus, original.sMinus);
    }

    private Stats analyzeImage(BufferedImage image) {
        ChannelStats red = analyzeChannel(image, 16);
        ChannelStats green = analyzeChannel(image, 8);
        ChannelStats blue = analyzeChannel(image, 0);
        return new Stats(
                average(red.rm, green.rm, blue.rm),
                average(red.sm, green.sm, blue.sm),
                average(red.rMinus, green.rMinus, blue.rMinus),
                average(red.sMinus, green.sMinus, blue.sMinus));
    }

    private static double average(double a, double b, double c) {
        return (a + b + c) / 3.0;
    }

    private ChannelStats analyzeChannel(BufferedImage image, int shift) {
        int width = image.getWidth();
        int height = image.getHeight();
        long regularM = 0;
        long singularM = 0;
        long regularMinus = 0;
        long singularMinus = 0;
        long groups = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x + 3 < width; x += 4) {
                int[] group = new int[4];
                for (int i = 0; i < 4; i++) {
                    group[i] = (image.getRGB(x + i, y) >> shift) & 0xFF;
                }
                groups++;
                int cM = classify(group, /* useF1= */ true);
                int cMinus = classify(group, /* useF1= */ false);
                if (cM > 0) {
                    regularM++;
                } else if (cM < 0) {
                    singularM++;
                }
                if (cMinus > 0) {
                    regularMinus++;
                } else if (cMinus < 0) {
                    singularMinus++;
                }
            }
        }

        if (groups == 0) {
            return new ChannelStats(0, 0, 0, 0);
        }
        return new ChannelStats(
                regularM / (double) groups,
                singularM / (double) groups,
                regularMinus / (double) groups,
                singularMinus / (double) groups);
    }

    /**
     * @param useF1 {@code true} applies F₁ on M; {@code false} applies F₋₁ on M
     */
    static int classify(int[] group, boolean useF1) {
        double original = smoothness(group);
        double after = smoothness(applyMask(group, useF1));
        return Double.compare(after, original);
    }

    static double smoothness(int[] group) {
        double sum = 0.0;
        for (int i = 0; i < group.length - 1; i++) {
            sum += Math.abs(group[i + 1] - group[i]);
        }
        return sum;
    }

    static int[] applyMask(int[] group, boolean useF1) {
        int[] out = group.clone();
        for (int i = 0; i < out.length; i++) {
            if (MASK_M[i] == 1) {
                out[i] = useF1 ? flipF1(out[i]) : flipFMinus1(out[i]);
            }
        }
        return out;
    }

    /** F₁: flip LSB (0↔1, 2↔3, …). */
    static int flipF1(int value) {
        return value ^ 1;
    }

    /** F₋₁(x) = F₁(x+1) − 1 (pairs …, −1↔0, 1↔2, 3↔4, …). */
    static int flipFMinus1(int value) {
        return flipF1(value + 1) - 1;
    }

    private static BufferedImage flipAllLsbs(BufferedImage source) {
        BufferedImage copy = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int rgb = source.getRGB(x, y);
                int r = ((rgb >> 16) & 0xFF) ^ 1;
                int g = ((rgb >> 8) & 0xFF) ^ 1;
                int b = (rgb & 0xFF) ^ 1;
                copy.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return copy;
    }

    /**
     * Fridrich RS quadratic. Roots are in a translated coordinate; the embedding
     * rate is {@code p = x / (x - 1/2)}.
     *
     * @param d0       R_M − S_M on the image
     * @param dMinus0  R_{-M} − S_{-M} on the image
     * @param d1       R_M − S_M on the LSB-flipped image
     * @param dMinus1  R_{-M} − S_{-M} on the LSB-flipped image
     */
    static double solveRate(double d0, double dMinus0, double d1, double dMinus1) {
        double a = 2.0 * (d1 + d0);
        double b = dMinus0 - dMinus1 - d1 - 3.0 * d0;
        double c = d0 - dMinus0;

        double x;
        if (Math.abs(a) < 1e-12) {
            if (Math.abs(b) < 1e-12) {
                return 0.0;
            }
            x = -c / b;
        } else {
            double discriminant = b * b - 4.0 * a * c;
            if (discriminant < 0) {
                return Double.NaN;
            }
            double sqrt = Math.sqrt(discriminant);
            double root1 = (-b + sqrt) / (2.0 * a);
            double root2 = (-b - sqrt) / (2.0 * a);
            x = Math.abs(root1) <= Math.abs(root2) ? root1 : root2;
        }

        if (Double.isNaN(x) || Double.isInfinite(x) || Math.abs(x - 0.5) < 1e-12) {
            return Double.NaN;
        }
        double p = x / (x - 0.5);
        if (Double.isNaN(p) || Double.isInfinite(p)) {
            return Double.NaN;
        }
        return Math.max(0.0, Math.min(1.0, Math.abs(p)));
    }

    private record Stats(double rm, double sm, double rMinus, double sMinus) {
    }

    private record ChannelStats(double rm, double sm, double rMinus, double sMinus) {
    }
}
