package com.jxzheng.whisper.crypto;

/**
 * Marks the integrity-only envelope suite. HMAC is applied by {@link Envelope},
 * not by {@link Stream}, because there are no per-chunk nonces.
 */
public final class IntegrityOnlySuite implements AeadSuite {

    public static final IntegrityOnlySuite INSTANCE = new IntegrityOnlySuite();
    public static final int TAG_BYTES = 32;

    private IntegrityOnlySuite() {
    }

    @Override
    public String id() {
        return "none";
    }

    @Override
    public String displayName() {
        return "None — integrity only";
    }

    @Override
    public int suiteBits() {
        return 2;
    }

    @Override
    public int overhead(int bodyLen) {
        return TAG_BYTES;
    }

    @Override
    public int seal(byte[] key32, byte[] nonce12, byte[] aad, byte[] in, int inOff, int inLen, byte[] out, int outOff) {
        throw new UnsupportedOperationException("integrity-only mode does not use STREAM");
    }

    @Override
    public int open(byte[] key32, byte[] nonce12, byte[] aad, byte[] in, int inOff, int inLen, byte[] out, int outOff) {
        throw new UnsupportedOperationException("integrity-only mode does not use STREAM");
    }
}
