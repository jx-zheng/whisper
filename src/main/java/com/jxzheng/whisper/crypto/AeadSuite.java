package com.jxzheng.whisper.crypto;

/**
 * One AEAD. {@link Stream} calls this once per 64 KiB chunk with a fresh nonce.
 * The nonce argument is required so a chunk never reuses a key and nonce.
 */
public interface AeadSuite {

    String id();

    String displayName();

    /** Wire value for envelope flag bits 5–6. */
    int suiteBits();

    /** Tag bytes added to a body of {@code bodyLen} bytes. */
    int overhead(int bodyLen);

    /**
     * Encrypts {@code inLen} bytes and writes ciphertext || 16-byte tag at {@code outOff}.
     *
     * @return number of bytes written
     */
    int seal(byte[] key32, byte[] nonce12, byte[] aad, byte[] in, int inOff, int inLen, byte[] out, int outOff)
            throws AuthException;

    /**
     * Decrypts ciphertext || tag. Writes plaintext at {@code outOff}.
     *
     * @return plaintext length
     */
    int open(byte[] key32, byte[] nonce12, byte[] aad, byte[] in, int inOff, int inLen, byte[] out, int outOff)
            throws AuthException;
}
