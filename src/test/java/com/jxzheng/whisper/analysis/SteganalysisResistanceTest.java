package com.jxzheng.whisper.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.jxzheng.whisper.schemes.ZhangTangScheme;

class SteganalysisResistanceTest {

    @Test
    void chiSquareSurvivalMatchesKnownCriticalValues() {
        assertEquals(0.05, ChiSquareDistribution.upperSurvival(3.841, 1), 0.002);
        assertTrue(ChiSquareDistribution.upperSurvival(0.0, 1) > 0.99);
        assertTrue(ChiSquareDistribution.upperSurvival(20.0, 1) < 1e-4);
        // Mid df sanity: χ²_50 critical ~67.5 at 5%
        double p50 = ChiSquareDistribution.upperSurvival(67.505, 50);
        assertEquals(0.05, p50, 0.01);
    }

    @Test
    void pairBalanceRisesUnderNaiveLsbOnBiasedCover() {
        BufferedImage cover = evenBiasedCover(96, 96, 3);
        BufferedImage naive = NaiveLsbEmbedder.embed(cover, fillBits(cover, 0.95));

        ChiSquareAnalyzer analyzer = new ChiSquareAnalyzer();
        double coverBalance = analyzer.analyze(cover).pairBalance();
        double naiveBalance = analyzer.analyze(naive).pairBalance();

        assertTrue(naiveBalance > coverBalance + 0.2,
                "naive LSB should equalize PoVs (cover=" + coverBalance + ", naive=" + naiveBalance + ")");
    }

    @Test
    void westfeldPValueRisesUnderNaiveLsbOnBiasedCover() {
        BufferedImage cover = evenBiasedCover(128, 128, 5);
        BufferedImage naive = NaiveLsbEmbedder.embed(cover, fillBits(cover, 0.95));

        ChiSquareAnalyzer analyzer = new ChiSquareAnalyzer();
        double coverP = analyzer.analyze(cover).pValue();
        double naiveP = analyzer.analyze(naive).pValue();

        assertTrue(naiveP > coverP,
                "naive LSB should raise Westfeld p (cover=" + coverP + ", naive=" + naiveP + ")");
    }

    @Test
    void rsMaskAsymmetryRisesUnderNaiveLsb() {
        BufferedImage cover = naturalCover(128, 128, 9);
        BufferedImage naive = NaiveLsbEmbedder.embed(cover, fillBits(cover, 0.7));

        RsAnalyzer analyzer = new RsAnalyzer();
        double coverAsym = analyzer.analyze(cover).maskAsymmetry();
        double naiveAsym = analyzer.analyze(naive).maskAsymmetry();

        assertTrue(naiveAsym > coverAsym,
                "naive LSB should increase RS mask asymmetry (cover=" + coverAsym
                        + ", naive=" + naiveAsym + ")");
    }

    @Test
    void solveRateRecoversKnownQuadraticRoot() {
        // Construct d's so the translated root x=0 ⇒ p=0.
        // With x=0: c must be 0 ⇒ d0 == dMinus0; and a,b arbitrary with consistent zero root.
        double rate = RsAnalyzer.solveRate(0.1, 0.1, -0.1, -0.1);
        assertEquals(0.0, rate, 1e-9);
    }

    @Test
    void flipFMinus1MatchesFridrichDefinition() {
        assertEquals(-1, RsAnalyzer.flipFMinus1(0));
        assertEquals(2, RsAnalyzer.flipFMinus1(1));
        assertEquals(1, RsAnalyzer.flipFMinus1(2));
        assertEquals(4, RsAnalyzer.flipFMinus1(3));
        assertEquals(RsAnalyzer.flipF1(5) - 1, RsAnalyzer.flipFMinus1(4));
    }

    @Test
    void zhangTangRoundTripAndPerturbsPoVsLessThanFullNaiveLsb() {
        BufferedImage cover = evenBiasedCover(128, 128, 21);
        byte[] payload = new byte[400];
        new Random(7).nextBytes(payload);

        BufferedImage zhangTang = new ZhangTangScheme(cover, "analysis-key").embedMessage(payload);
        BufferedImage naiveFull = NaiveLsbEmbedder.embed(cover, fillBits(cover, 0.9));

        byte[] recovered = new ZhangTangScheme(zhangTang, "analysis-key").extractMessage();
        assertTrue(Arrays.equals(payload, recovered));

        ChiSquareAnalyzer chi = new ChiSquareAnalyzer();
        double coverBalance = chi.analyze(cover).pairBalance();
        double ztLift = Math.abs(chi.analyze(zhangTang).pairBalance() - coverBalance);
        double naiveLift = Math.abs(chi.analyze(naiveFull).pairBalance() - coverBalance);

        assertTrue(Double.isFinite(chi.analyze(zhangTang).pValue()));
        assertTrue(naiveLift > ztLift,
                "full naive LSB should move PoV balance more than sparse Zhang-Tang (zt="
                        + ztLift + ", naive=" + naiveLift + ")");
    }

    private static BufferedImage evenBiasedCover(int width, int height, long seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = random.nextInt(128) * 2;
                int g = random.nextInt(128) * 2;
                int b = random.nextInt(128) * 2;
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    private static BufferedImage naturalCover(int width, int height, long seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int base = (x * 3 + y * 5) & 0x7F;
                int r = Math.min(255, base + random.nextInt(80));
                int g = Math.min(255, base + random.nextInt(80));
                int b = Math.min(255, base + random.nextInt(80));
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    private static byte[] fillBits(BufferedImage cover, double fraction) {
        int capacityBytes = (cover.getWidth() * cover.getHeight() * 3) / 8;
        int bytes = Math.max(1, (int) (capacityBytes * fraction));
        byte[] payload = new byte[bytes];
        new Random(42).nextBytes(payload);
        return payload;
    }
}
