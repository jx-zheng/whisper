package com.jxzheng.whisper.encryption;

import com.jxzheng.whisper.exceptions.EncryptionException;

/**
 * Encrypts and decrypts opaque byte payloads.
 */
public interface CipherService {

    byte[] encrypt(byte[] plaintext, String key) throws EncryptionException;

    byte[] decrypt(byte[] ciphertext, String key) throws EncryptionException;
}
