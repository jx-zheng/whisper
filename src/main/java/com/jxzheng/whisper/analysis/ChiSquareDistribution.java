package com.jxzheng.whisper.analysis;

/**
 * Minimal chi-square right-tail probabilities via the regularized gamma Q.
 */
final class ChiSquareDistribution {

    private ChiSquareDistribution() {
    }

    static double upperSurvival(double chiSquare, int degreesOfFreedom) {
        if (chiSquare <= 0.0) {
            return 1.0;
        }
        if (degreesOfFreedom <= 0) {
            throw new IllegalArgumentException("degreesOfFreedom must be positive");
        }
        return regularizedGammaQ(degreesOfFreedom / 2.0, chiSquare / 2.0);
    }

    private static double regularizedGammaQ(double a, double x) {
        if (x < 0 || a <= 0) {
            return Double.NaN;
        }
        if (x == 0) {
            return 1.0;
        }
        if (x < a + 1.0) {
            return 1.0 - regularizedGammaPSeries(a, x);
        }
        return regularizedGammaQContinuedFraction(a, x);
    }

    private static double regularizedGammaPSeries(double a, double x) {
        final int maxIterations = 200;
        final double epsilon = 1e-12;
        double ap = a;
        double sum = 1.0 / a;
        double term = sum;
        for (int n = 1; n <= maxIterations; n++) {
            ap += 1.0;
            term *= x / ap;
            sum += term;
            if (Math.abs(term) < Math.abs(sum) * epsilon) {
                break;
            }
        }
        return sum * Math.exp(-x + a * Math.log(x) - logGamma(a));
    }

    private static double regularizedGammaQContinuedFraction(double a, double x) {
        final int maxIterations = 200;
        final double epsilon = 1e-12;
        final double tiny = 1e-30;

        double b = x + 1.0 - a;
        double c = 1.0 / tiny;
        double d = 1.0 / b;
        double h = d;

        for (int i = 1; i <= maxIterations; i++) {
            double an = -i * (i - a);
            b += 2.0;
            d = an * d + b;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = b + an / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1.0 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1.0) < epsilon) {
                break;
            }
        }
        return Math.exp(-x + a * Math.log(x) - logGamma(a)) * h;
    }

    private static double logGamma(double z) {
        double[] coefficients = {
                76.18009172947146,
                -86.50532032941677,
                24.01409824083091,
                -1.231739572450155,
                0.1208650973866179e-2,
                -0.5395239384953e-5
        };
        double x = z;
        double y = z;
        double tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (double coefficient : coefficients) {
            ser += coefficient / ++y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }
}
