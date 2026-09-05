package com.jxzheng.whisper.media;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Helpers for choosing and validating image formats used with LSB steganography.
 *
 * <p>Embed outputs must be an allow-listed RGB container (PNG or BMP). GIF is
 * excluded because ImageIO typically quantizes to a palette, which destroys LSBs
 * even though GIF is "lossless" for indexed data. Cover warnings use an extension
 * heuristic only (not magic-byte sniffing).
 */
public final class ImageFormats {

    public static final String PREFERRED_FORMAT = "png";

    /** Formats safe for stego output via ImageIO without palette quantization. */
    private static final Set<String> SAFE_STEGO_OUTPUT = Set.of("png", "bmp");

    /** Extensions that usually indicate prior lossy compression of a cover. */
    private static final Set<String> LOSSY_FORMATS = Set.of("jpg", "jpeg", "jpe", "jfif", "webp");

    private ImageFormats() {
    }

    public static String extensionOf(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return "";
        }
        String name = fileName.toString();
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
        Objects.requireNonNull(format, "format");
        return LOSSY_FORMATS.contains(format.toLowerCase(Locale.ROOT));
    }

    public static boolean isSafeStegoOutput(String format) {
        Objects.requireNonNull(format, "format");
        return SAFE_STEGO_OUTPUT.contains(format.toLowerCase(Locale.ROOT));
    }

    /**
     * Validates that an embed output path uses an allow-listed lossless RGB format.
     *
     * @throws IllegalArgumentException if the format is not PNG or BMP
     */
    public static void requireLosslessOutput(Path outputPath) {
        String format = formatOf(outputPath);
        if (!isSafeStegoOutput(format)) {
            throw new IllegalArgumentException(
                    "Refusing to write stego image as ."
                            + (extensionOf(outputPath).isEmpty() ? format : extensionOf(outputPath))
                            + " — use PNG or BMP (e.g. "
                            + replaceExtension(outputPath, PREFERRED_FORMAT) + ").");
        }
    }

    /**
     * @return a warning message if the cover filename looks lossy, otherwise {@code null}
     */
    public static String lossyInputWarning(Path inputPath) {
        String format = formatOf(inputPath);
        if (!isLossy(format)) {
            return null;
        }
        return "Warning: cover image is ." + extensionOf(inputPath)
                + " (lossy by extension). Prefer a lossless PNG cover so prior compression "
                + "artifacts don't reduce capacity or reliability.";
    }

    public static Path replaceExtension(Path path, String newExtension) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("Path has no file name: " + path);
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        return path.resolveSibling(base + "." + newExtension);
    }
}
