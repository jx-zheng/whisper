package com.jxzheng.whisper.crypto;

/**
 * age-style STREAM: 64 KiB chunks, nonce {@code 7×0x00 ‖ u32BE(seq) ‖ lastFlag}.
 * Chunk 0 authenticates {@code aad}. Later chunks have empty associated data.
 */
public final class Stream {

    public static final int CHUNK = 64 * 1024;
    public static final int TAG = 16;

    private Stream() {
    }

    public static int cipherLen(int plaintextLen) {
        if (plaintextLen <= 0) {
            return TAG;
        }
        int full = plaintextLen / CHUNK;
        int rem = plaintextLen % CHUNK;
        if (rem == 0) {
            return full * (CHUNK + TAG);
        }
        return full * (CHUNK + TAG) + rem + TAG;
    }

    public static int seal(AeadSuite suite, byte[] key, byte[] aad, byte[] in, int inOff, int inLen, byte[] out, int outOff)
            throws AuthException {
        int written = 0;
        int seq = 0;
        if (inLen <= 0) {
            byte[] nonce = nonce(0, true);
            try {
                return suite.seal(key, nonce, aad, in, inOff, 0, out, outOff);
            } finally {
                Secrets.wipe(nonce);
            }
        }
        int off = 0;
        while (off < inLen) {
            int take = Math.min(CHUNK, inLen - off);
            boolean last = off + take == inLen;
            byte[] nonce = nonce(seq, last);
            try {
                int n = suite.seal(key, nonce, seq == 0 ? aad : null, in, inOff + off, take, out, outOff + written);
                written += n;
            } finally {
                Secrets.wipe(nonce);
            }
            off += take;
            seq++;
        }
        return written;
    }

    /**
     * Decrypts a STREAM body. Plaintext is written into a new array that the
     * caller wipes. A failure wipes that array and throws.
     */
    public static byte[] open(AeadSuite suite, byte[] key, byte[] aad, byte[] ct) throws AuthException {
        if (ct == null || ct.length < TAG) {
            throw new AuthException(AuthException.Kind.INCOMPLETE, suite.suiteBits(), "truncated stream");
        }
        byte[] plain = new byte[ct.length - TAG];
        int filled = 0;
        int offset = 0;
        int seq = 0;
        boolean sawLast = false;
        try {
            while (offset < ct.length) {
                int rest = ct.length - offset;
                int take = rest;
                boolean last = true;
                if (rest > CHUNK + TAG) {
                    take = CHUNK + TAG;
                    last = false;
                }
                if (take < TAG) {
                    throw new AuthException(AuthException.Kind.INCOMPLETE, suite.suiteBits(), "short chunk");
                }
                byte[] nonce = nonce(seq, last);
                int n;
                try {
                    n = suite.open(key, nonce, seq == 0 ? aad : null, ct, offset, take, plain, filled);
                } catch (AuthException e) {
                    throw new AuthException(AuthException.Kind.AUTH, suite.suiteBits(), "chunk authentication failed");
                } finally {
                    Secrets.wipe(nonce);
                }
                if (!last && n != CHUNK) {
                    throw new AuthException(AuthException.Kind.INCOMPLETE, suite.suiteBits(), "short intermediate chunk");
                }
                if (last && n == 0 && seq != 0) {
                    throw new AuthException(AuthException.Kind.INCOMPLETE, suite.suiteBits(), "empty final chunk");
                }
                filled += n;
                offset += take;
                sawLast = last;
                if (last) {
                    break;
                }
                seq++;
            }
            if (!sawLast || offset != ct.length) {
                throw new AuthException(AuthException.Kind.INCOMPLETE, suite.suiteBits(), "stream did not end on the final chunk");
            }
            if (filled == plain.length) {
                return plain;
            }
            byte[] exact = new byte[filled];
            System.arraycopy(plain, 0, exact, 0, filled);
            return exact;
        } catch (AuthException e) {
            Secrets.wipe(plain);
            throw e;
        } finally {
            if (filled != plain.length) {
                Secrets.wipe(plain);
            }
        }
    }

    static byte[] nonce(int seq, boolean last) {
        byte[] nonce = new byte[12];
        nonce[7] = (byte) (seq >>> 24);
        nonce[8] = (byte) (seq >>> 16);
        nonce[9] = (byte) (seq >>> 8);
        nonce[10] = (byte) seq;
        if (last) {
            nonce[11] = 1;
        }
        return nonce;
    }
}
