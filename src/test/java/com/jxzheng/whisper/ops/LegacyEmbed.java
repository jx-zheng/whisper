package com.jxzheng.whisper.ops;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import javax.imageio.ImageIO;

import com.jxzheng.whisper.encryption.AesCipher;
import com.jxzheng.whisper.encryption.CipherService;
import com.jxzheng.whisper.exceptions.EncryptionException;
import com.jxzheng.whisper.exceptions.MessageTooLongException;
import com.jxzheng.whisper.media.ImageFormats;
import com.jxzheng.whisper.schemes.AbstractScheme;
import com.jxzheng.whisper.schemes.ZhangTangScheme;

/** Test-only Zhang–Tang embed. Production code does not create 0.9 payloads. */
public final class LegacyEmbed {

    public record Result(BufferedImage image, int payloadBytes) {
    }

    private LegacyEmbed() {
    }

    public static Result embed(
            BufferedImage cover,
            byte[] message,
            String stegoKey,
            boolean encrypt,
            String encryptKey) throws EncryptionException {
        return embed(cover, message, stegoKey, encrypt, encryptKey, "zhang-tang", "aes");
    }

    public static Result embed(
            BufferedImage cover,
            byte[] message,
            String stegoKey,
            boolean encrypt,
            String encryptKey,
            String scheme,
            String cipher) throws EncryptionException {
        if (cover == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        String walkKey = requireStegoKey(stegoKey);
        canonicalScheme(scheme);
        String cipherKey = encrypt ? requireEncryptKey(encryptKey) : null;
        CipherService cipherService = encrypt ? createCipher(cipher) : null;
        if (!fits(cover.getWidth(), cover.getHeight(), message.length, encrypt)) {
            throw capacityError(cover.getWidth(), cover.getHeight(), message.length, encrypt);
        }
        byte[] payload = cipherService == null ? message : cipherService.encrypt(message, cipherKey);
        if (payload.length > AbstractScheme.MAX_PAYLOAD_LENGTH) {
            throw new MessageTooLongException(
                    "Message exceeds max message length of " + AbstractScheme.MAX_PAYLOAD_LENGTH);
        }
        BufferedImage stego = new ZhangTangScheme(cover, walkKey).embedMessage(payload);
        return new Result(stego, payload.length);
    }

    private static MessageTooLongException capacityError(int width, int height, int plaintextBytes, boolean encrypt) {
        int max = maxPayloadBytes(width, height);
        if (max < 0) {
            int needed = AbstractScheme.HEADER_POINTS + 1;
            return new MessageTooLongException(
                    "Image is too small to hold a whisper payload (need at least " + needed + " pixels).");
        }
        int packaged = packagedLength(plaintextBytes, encrypt);
        String extra = encrypt ? " AES-GCM adds " + AesCipher.OVERHEAD_BYTES + " bytes." : "";
        return new MessageTooLongException(
                "Message needs " + packaged + " embedded byte(s) but this image can hold " + max + "." + extra);
    }

    private static String canonicalScheme(String scheme) {
        String name = (scheme == null || scheme.isBlank()) ? "zhang-tang" : scheme.toLowerCase(Locale.ROOT).trim();
        return switch (name) {
            case "zhang-tang", "zhangtang", "zt" -> "zhang-tang";
            default -> throw new IllegalArgumentException("Unsupported scheme: " + name);
        };
    }

    private static CipherService createCipher(String cipher) {
        String name = (cipher == null || cipher.isBlank()) ? "aes" : cipher.toLowerCase(Locale.ROOT).trim();
        return switch (name) {
            case "aes" -> new AesCipher();
            default -> throw new IllegalArgumentException("Unsupported cipher: " + name);
        };
    }

    private static String requireStegoKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException(
                    "Stego key must not be null; use an empty string for the public default walk");
        }
        return key;
    }

    public static byte[] extract(
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
