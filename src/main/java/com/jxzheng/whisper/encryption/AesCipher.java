package com.jxzheng.whisper.encryption;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import com.jxzheng.whisper.exceptions.EncryptionException;

/**
 * AES-128-CBC with PKCS5 padding. Ciphertext layout:
 * {@code [16-byte salt][16-byte IV][ciphertext...]}.
 * The key is derived via PBKDF2-HMAC-SHA256.
 */
public class AesCipher implements CipherService {

    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final String KEY_ALGORITHM = "AES";
    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int AES_KEY_BYTES = 16;
    private static final int IV_BYTES = 16;
    private static final int SALT_BYTES = 16;
    private static final int PBKDF2_ITERATIONS = 65_536;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public byte[] encrypt(byte[] plaintext, String key) throws EncryptionException {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        requireKey(key);

        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(salt);
        secureRandom.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(key, salt), new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] result = new byte[SALT_BYTES + IV_BYTES + ciphertext.length];
            System.arraycopy(salt, 0, result, 0, SALT_BYTES);
            System.arraycopy(iv, 0, result, SALT_BYTES, IV_BYTES);
            System.arraycopy(ciphertext, 0, result, SALT_BYTES + IV_BYTES, ciphertext.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Failed to encrypt with AES", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertext, String key) throws EncryptionException {
        if (ciphertext == null) {
            throw new IllegalArgumentException("ciphertext must not be null");
        }
        requireKey(key);
        if (ciphertext.length <= SALT_BYTES + IV_BYTES) {
            throw new EncryptionException("Ciphertext too short to contain salt, IV, and payload");
        }

        byte[] salt = Arrays.copyOfRange(ciphertext, 0, SALT_BYTES);
        byte[] iv = Arrays.copyOfRange(ciphertext, SALT_BYTES, SALT_BYTES + IV_BYTES);
        byte[] encrypted = Arrays.copyOfRange(ciphertext, SALT_BYTES + IV_BYTES, ciphertext.length);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(key, salt), new IvParameterSpec(iv));
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Failed to decrypt with AES", e);
        }
    }

    private static SecretKey deriveKey(String key, byte[] salt) throws GeneralSecurityException {
        KeySpec spec = new PBEKeySpec(key.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BYTES * 8);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }

    private static void requireKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must not be null or empty");
        }
    }
}
