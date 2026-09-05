package com.jxzheng.whisper.encryption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.jxzheng.whisper.exceptions.EncryptionException;

class AesCipherTest {

    private final AesCipher cipher = new AesCipher();

    @Test
    void roundTripEncryptDecrypt() throws EncryptionException {
        byte[] plaintext = "hello whisper".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = cipher.encrypt(plaintext, "correct horse battery staple");
        byte[] recovered = cipher.decrypt(ciphertext, "correct horse battery staple");
        assertArrayEquals(plaintext, recovered);
    }

    @Test
    void encryptionIsNondeterministic() throws EncryptionException {
        byte[] plaintext = "same message".getBytes(StandardCharsets.UTF_8);
        byte[] first = cipher.encrypt(plaintext, "passphrase");
        byte[] second = cipher.encrypt(plaintext, "passphrase");
        assertFalse(java.util.Arrays.equals(first, second));
    }

    @Test
    void wrongPassphraseFails() throws EncryptionException {
        byte[] ciphertext = cipher.encrypt("secret".getBytes(StandardCharsets.UTF_8), "right");
        assertThrows(EncryptionException.class, () -> cipher.decrypt(ciphertext, "wrong"));
    }

    @Test
    void ciphertextIncludesSaltAndIv() throws EncryptionException {
        byte[] ciphertext = cipher.encrypt("x".getBytes(StandardCharsets.UTF_8), "key");
        // 16-byte salt + 12-byte IV + ciphertext/tag
        assertTrue(ciphertext.length > 28);
    }
}
