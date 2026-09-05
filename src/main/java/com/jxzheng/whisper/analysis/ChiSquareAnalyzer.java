package com.jxzheng.whisper.analysis;

import java.awt.image.BufferedImage;

/**
 * Westfeld–Pfitzmann chi-square steganalysis over RGB sample histograms.
 *
 * <p>Classic LSB embedding equalizes adjacent even/odd histogram bins (PoVs).
 * Under the equal-PoV null, a <strong>high</strong> p-value is stego-like.
 */
public final class ChiSquareAnalyzer {

    public record Result(
            double chiSquare,
            int degreesOfFreedom,
            double pValue,
            double pairBalance,
            long samples) {
        /** Westfeld: high p ⇒ PoVs look equalized (classic-LSB-like). */
        public boolean stegoLike(double alpha) {
            return pValue > alpha;
        }
    }

    public Result analyze(BufferedImage image) {
        long[] histogram = new long[256];
        long samples = 0L;
        int width = image.getWidth();
        int height = image.getHeight();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                histogram[(rgb >> 16) & 0xFF]++;
                histogram[(rgb >> 8) & 0xFF]++;
                histogram[rgb & 0xFF]++;
                samples += 3;
            }
        }

        double chiSquare = 0.0;
        double balanceSum = 0.0;
        int pairsUsed = 0;
        for (int i = 0; i < 128; i++) {
            long even = histogram[2 * i];
            long odd = histogram[2 * i + 1];
            long total = even + odd;
            if (total < 2) {
                continue;
            }
            double expected = total / 2.0;
            chiSquare += Math.pow(even - expected, 2) / expected;
            chiSquare += Math.pow(odd - expected, 2) / expected;
            balanceSum += 1.0 - (Math.abs(even - odd) / (double) total);
            pairsUsed++;
        }

        int df = Math.max(1, pairsUsed - 1);
        double pValue = ChiSquareDistribution.upperSurvival(chiSquare, df);
        double pairBalance = pairsUsed == 0 ? 0.0 : balanceSum / pairsUsed;
        return new Result(chiSquare, df, pValue, pairBalance, samples);
    }
}
