package com.jxzheng.whisper.analysis;

import java.awt.image.BufferedImage;

/**
 * Fridrich RS (Regular/Singular) steganalysis.
 *
 * <p>Estimates classic LSB embedding rate from non-overlapping 4-pixel groups
 * using discrimination function {@code Σ|xᵢ₊₁-xᵢ|} and flip masks M / −M.
 */
public final class RsAnalyzer {

    private static final int[] MASK_M = {0, 1, 1, 0};
    private static final int[] MASK_MINUS_M = {1, 0, 0, 1};

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

        double d0 = original.rm - original.sm;
        double d1 = original.rMinus - original.sMinus;
        double dInf = flipped.rm - flipped.sm;
        double dMinusInf = flipped.rMinus - flipped.sMinus;

        double rate = solveRate(d0, d1, dInf, dMinusInf);
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
                int cM = classify(group, MASK_M);
                int cMinus = classify(group, MASK_MINUS_M);
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

    static int classify(int[] group, int[] mask) {
        double original = smoothness(group);
        double after = smoothness(flip(group, mask));
        return Double.compare(after, original);
    }

    static double smoothness(int[] group) {
        double sum = 0.0;
        for (int i = 0; i < group.length - 1; i++) {
            sum += Math.abs(group[i + 1] - group[i]);
        }
        return sum;
    }

    static int[] flip(int[] group, int[] mask) {
        int[] out = group.clone();
        for (int i = 0; i < out.length; i++) {
            if (mask[i] == 1) {
                out[i] ^= 1;
            }
        }
        return out;
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

    static double solveRate(double d0, double d1, double dInf, double dMinusInf) {
        double a = 2.0 * (dInf + d0);
        double b = dMinusInf - dInf - d1 - 3.0 * d0;
        double c = d0 - dInf;

        double rate;
        if (Math.abs(a) < 1e-12) {
            rate = Math.abs(b) < 1e-12 ? 0.0 : -c / b;
        } else {
            double discriminant = Math.max(0.0, b * b - 4.0 * a * c);
            double sqrt = Math.sqrt(discriminant);
            double root1 = (-b + sqrt) / (2.0 * a);
            double root2 = (-b - sqrt) / (2.0 * a);
            rate = Math.abs(root1) <= Math.abs(root2) ? root1 : root2;
        }
        if (Double.isNaN(rate) || Double.isInfinite(rate)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, rate));
    }

    private record Stats(double rm, double sm, double rMinus, double sMinus) {
    }

    private record ChannelStats(double rm, double sm, double rMinus, double sMinus) {
    }
}
