package com.jxzheng.whisper.crypto;

import com.jxzheng.whisper.encryption.AesCipher;
import com.jxzheng.whisper.exceptions.EncryptionException;

/**
 * Whisper 0.9 payload cipher: AES-128-GCM with PBKDF2-HMAC-SHA256.
 * Decrypt only. New embeds use {@link Envelope}.
 */
public final class LegacyAes128GcmPbkdf2 {

    private final AesCipher cipher = new AesCipher();

    public byte[] decrypt(byte[] ciphertext, char[] passphrase) throws EncryptionException {
        if (passphrase == null) {
            throw new IllegalArgumentException(
                    "key must not be null; an empty string is the public empty-passphrase AES-GCM key");
        }
        try {
            return cipher.decrypt(ciphertext, new String(passphrase));
        } finally {
            Secrets.wipe(passphrase);
        }
    }
}
