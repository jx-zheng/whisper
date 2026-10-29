package com.jxzheng.whisper.ops;

import java.awt.image.BufferedImage;
import java.util.Locale;

import com.jxzheng.whisper.encryption.AesCipher;
import com.jxzheng.whisper.encryption.CipherService;
import com.jxzheng.whisper.exceptions.EncryptionException;
import com.jxzheng.whisper.exceptions.MessageTooLongException;
import com.jxzheng.whisper.schemes.AbstractScheme;
import com.jxzheng.whisper.schemes.ZhangTangScheme;

/** Test-only Zhang–Tang embed. Production code does not create 0.9 payloads. */
public final class LegacyEmbed {

    private LegacyEmbed() {
    }

    public static StegoOperations.EmbedResult embed(
            BufferedImage cover,
            byte[] message,
            String stegoKey,
            boolean encrypt,
            String encryptKey) throws EncryptionException {
        return embed(cover, message, stegoKey, encrypt, encryptKey, "zhang-tang", "aes");
    }

    public static StegoOperations.EmbedResult embed(
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
        if (!StegoOperations.fits(cover.getWidth(), cover.getHeight(), message.length, encrypt)) {
            throw capacityError(cover.getWidth(), cover.getHeight(), message.length, encrypt);
        }
        byte[] payload = cipherService == null ? message : cipherService.encrypt(message, cipherKey);
        if (payload.length > AbstractScheme.MAX_PAYLOAD_LENGTH) {
            throw new MessageTooLongException(
                    "Message exceeds max message length of " + AbstractScheme.MAX_PAYLOAD_LENGTH);
        }
        BufferedImage stego = new ZhangTangScheme(cover, walkKey).embedMessage(payload);
        return new StegoOperations.EmbedResult(stego, payload.length);
    }

    private static MessageTooLongException capacityError(int width, int height, int plaintextBytes, boolean encrypt) {
        int max = StegoOperations.maxPayloadBytes(width, height);
        if (max < 0) {
            int needed = AbstractScheme.HEADER_POINTS + 1;
            return new MessageTooLongException(
                    "Image is too small to hold a whisper payload (need at least " + needed + " pixels).");
        }
        int packaged = StegoOperations.packagedLength(plaintextBytes, encrypt);
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
