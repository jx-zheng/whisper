package com.jxzheng.whisper.ops;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import com.jxzheng.whisper.analysis.ChiSquareAnalyzer;
import com.jxzheng.whisper.analysis.RsAnalyzer;
import com.jxzheng.whisper.crypto.CipherRegistry;
import com.jxzheng.whisper.encryption.AesCipher;
import com.jxzheng.whisper.encryption.CipherService;
import com.jxzheng.whisper.exceptions.EncryptionException;
import com.jxzheng.whisper.media.ImageFormats;
import com.jxzheng.whisper.schemes.AbstractScheme;
import com.jxzheng.whisper.schemes.SchemeRegistry;
import com.jxzheng.whisper.schemes.ZhangTangScheme;

/**
 * Embed, extract, and analyze entry points shared by the CLI and the desktop app.
 */
public final class StegoOperations {

    private static final String LEGACY_SCHEME = "zhang-tang";
    private static final String LEGACY_CIPHER = "aes";

    public List<String> schemes() {
        return SchemeRegistry.shared().ids();
    }

    public List<String> ciphers() {
        return CipherRegistry.shared().ids();
    }

    public record EmbedResult(BufferedImage image, int payloadBytes) {
    }

    public record Report(ChiSquareAnalyzer.Result chiSquare, RsAnalyzer.Result rs) {
    }

    /**
     * Extracts a 0.9 payload. {@code stegoKey} must be the walk used at embed time,
     * including the empty public default. When {@code decrypt} is true,
     * {@code encryptKey} is the AES-GCM passphrase and is never taken from
     * {@code stegoKey}.
     */
    public byte[] extract(
            BufferedImage stego,
            String stegoKey,
            boolean decrypt,
            String encryptKey,
            String scheme,
            String cipher) throws EncryptionException {
        if (stego == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        String walkKey = requireStegoKey(stegoKey);
        String cipherKey = decrypt ? requireEncryptKey(encryptKey) : null;
        if (decrypt) {
            createCipher(cipher);
        }
        canonicalScheme(scheme);
        byte[] payload = new ZhangTangScheme(stego, walkKey).extractMessage();
        if (decrypt) {
            payload = createCipher(cipher).decrypt(payload, cipherKey);
        }
        return payload;
    }

    public Report analyze(BufferedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        return new Report(new ChiSquareAnalyzer().analyze(image), new RsAnalyzer().analyze(image));
    }

    /**
     * Largest payload, in bytes, that can be embedded in an image of this size.
     *
     * @return {@code -1} when the image cannot hold even an empty message
     */
    public static int maxPayloadBytes(int width, int height) {
        if (width <= 0 || height <= 0) {
            return -1;
        }
        long capacity = (long) width * (long) height;
        long bodyPixels = capacity - AbstractScheme.HEADER_POINTS;
        if (bodyPixels < 1) {
            return -1;
        }
        long bitPixels = bodyPixels - 1;
        long bytes = (bitPixels * AbstractScheme.USABLE_BITS_PER_PIXEL) / AbstractScheme.BITS_PER_BYTE;
        if (bytes > AbstractScheme.MAX_PAYLOAD_LENGTH) {
            return AbstractScheme.MAX_PAYLOAD_LENGTH;
        }
        return (int) bytes;
    }

    /**
     * Embedded size of a plaintext. AES-GCM adds a fixed salt, IV, and tag.
     */
    public static int packagedLength(int plaintextBytes, boolean encrypt) {
        if (plaintextBytes < 0) {
            throw new IllegalArgumentException("plaintextBytes must not be negative");
        }
        long packaged = encrypt ? (long) plaintextBytes + AesCipher.OVERHEAD_BYTES : plaintextBytes;
        return packaged > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) packaged;
    }

    public static boolean fits(int width, int height, int plaintextBytes, boolean encrypt) {
        int max = maxPayloadBytes(width, height);
        return max >= 0 && packagedLength(plaintextBytes, encrypt) <= max;
    }

    public static BufferedImage readImage(Path path) throws IOException {
        if (path == null || !Files.exists(path) || Files.isDirectory(path)) {
            throw new IOException("Could not read image: " + path);
        }
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new IOException("Could not read image: " + path);
        }
        return image;
    }

    public static void writeStego(BufferedImage image, Path path) throws IOException {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        if (path == null) {
            throw new IOException("Output path is missing");
        }
        ImageFormats.requireLosslessOutput(path);
        Path parent = path.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            throw new IOException("Output directory does not exist: " + parent);
        }
        String format = ImageFormats.formatOf(path);
        if (!ImageIO.write(image, format, path.toFile())) {
            throw new IOException("No ImageIO writer for format: " + format);
        }
    }

    /**
     * Reads a message file the same way the CLI does: UTF-8 text, then UTF-8 bytes.
     */
    public static byte[] readMessageFile(Path path) throws IOException {
        if (path == null) {
            throw new IOException("Message file is missing");
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            throw new IOException("Message file is not valid UTF-8: " + path, e);
        }
    }

    private static String canonicalScheme(String scheme) {
        String name = (scheme == null || scheme.isBlank())
                ? LEGACY_SCHEME
                : scheme.toLowerCase(Locale.ROOT).trim();
        return switch (name) {
            case "zhang-tang", "zhangtang", "zt" -> "zhang-tang";
            default -> throw new IllegalArgumentException("Unsupported scheme: " + name);
        };
    }

    private static CipherService createCipher(String cipher) {
        String name = (cipher == null || cipher.isBlank())
                ? LEGACY_CIPHER
                : cipher.toLowerCase(Locale.ROOT).trim();
        return switch (name) {
            case "aes" -> new AesCipher();
            default -> throw new IllegalArgumentException("Unsupported cipher: " + name);
        };
    }

    /** Empty is the public default walk. Null is a caller error. */
    private static String requireStegoKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException(
                    "Stego key must not be null; use an empty string for the public default walk");
        }
        return key;
    }

    /**
     * Required whenever encryption is on. Empty is the public AES-GCM key.
     * The stego key is never substituted here.
     */
    private static String requireEncryptKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException(
                    "Encryption passphrase is required when encryption is enabled. "
                            + "It is not taken from the stego key. "
                            + "Pass an empty string to use the public empty-string AES-GCM key.");
        }
        return key;
    }
}
