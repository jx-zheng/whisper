package com.jxzheng.whisper.crypto;

/**
 * Authentication failed, or the envelope is not a usable Whisper v2 blob.
 * Callers must not treat any bytes from a failed open as plaintext.
 */
public final class AuthException extends Exception {

    public enum Kind {
        /** Header is not a v2 envelope, or a length is over the cap. */
        FORMAT,
        /** AEAD tag or HMAC failed. */
        AUTH,
        /** Envelope salt does not match the public salt. */
        SALT,
        /** STREAM ended early, ran long, or the final chunk flag was wrong. */
        INCOMPLETE,
        /** Authenticated body did not inflate to the declared length. */
        DECOMPRESS
    }

    private final Kind kind;
    private final int suiteBits;

    public AuthException(Kind kind, String message) {
        this(kind, -1, message);
    }

    public AuthException(Kind kind, int suiteBits, String message) {
        super(message);
        this.kind = kind;
        this.suiteBits = suiteBits;
    }

    public Kind kind() {
        return kind;
    }

    /** Wire suite bits, or {@code -1} when the header could not be read. */
    public int suiteBits() {
        return suiteBits;
    }
}
