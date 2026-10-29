package com.jxzheng.whisper.ops;

import java.util.Locale;

/**
 * Shared number formats for chi-square and RS results.
 * The CLI and the desktop app both use these so the figures match.
 */
public final class AnalysisFormat {

    private AnalysisFormat() {
    }

    public static String pValue(double value) {
        return String.format(Locale.ROOT, "%.6g", value);
    }

    public static String fixed(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    public static String rate(double value) {
        return Double.isFinite(value) ? fixed(value) : "n/a";
    }

    public static String integer(long value) {
        return String.format(Locale.US, "%,d", value);
    }
}
