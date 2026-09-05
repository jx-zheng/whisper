package com.jxzheng.whisper.media;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Helpers for choosing and validating image formats used with LSB steganography.
 * Lossy codecs destroy embedded bits; PNG is the preferred container.
 */
public final class ImageFormats {

    public static final String PREFERRED_FORMAT = "png";

    private static final Set<String> LOSSY_FORMATS = Set.of("jpg", "jpeg", "jpe", "jfif", "webp");
    private static final Set<String> LOSSLESS_FORMATS = Set.of("png", "bmp", "gif");

    private ImageFormats() {
    }

    public static String extensionOf(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String formatOf(Path path) {
        String extension = extensionOf(path);
        if (extension.isEmpty()) {
            return PREFERRED_FORMAT;
        }
        if ("jpeg".equals(extension) || "jpe".equals(extension) || "jfif".equals(extension)) {
            return "jpg";
        }
        return extension;
    }

    public static boolean isLossy(String format) {
        return LOSSY_FORMATS.contains(format.toLowerCase(Locale.ROOT));
    }

    public static boolean isLossless(String format) {
        return LOSSLESS_FORMATS.contains(format.toLowerCase(Locale.ROOT));
    }

    /**
     * Validates that an embed output path uses a lossless format.
     *
     * @throws IllegalArgumentException if the format is lossy
     */
    public static void requireLosslessOutput(Path outputPath) {
        String format = formatOf(outputPath);
        if (isLossy(format)) {
            throw new IllegalArgumentException(
                    "Refusing to write stego image as ." + extensionOf(outputPath)
                            + " — lossy formats destroy embedded LSBs. Use PNG (e.g. "
                            + replaceExtension(outputPath, PREFERRED_FORMAT) + ").");
        }
    }

    /**
     * @return a warning message if the cover image is lossy, otherwise {@code null}
     */
    public static String lossyInputWarning(Path inputPath) {
        String format = formatOf(inputPath);
        if (!isLossy(format)) {
            return null;
        }
        return "Warning: cover image is ." + extensionOf(inputPath)
                + " (lossy). Prefer a lossless PNG cover so prior compression artifacts "
                + "don't reduce capacity or reliability.";
    }

    public static Path replaceExtension(Path path, String newExtension) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        Path parent = path.getParent();
        Path replaced = Path.of(base + "." + newExtension);
        return parent == null ? replaced : parent.resolve(replaced);
    }
}
