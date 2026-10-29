package com.jxzheng.whisper.crypto;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

/**
 * HKDF-SHA-256 (RFC 5869). A null or empty salt is the standard HashLen zero salt.
 */
public final class Hkdf {

    private Hkdf() {
    }

    public static byte[] expand(byte[] ikm, byte[] salt, String info, int length) {
        byte[] infoBytes = info == null ? new byte[0] : info.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return expand(ikm, salt, infoBytes, length);
    }

    public static byte[] expand(byte[] ikm, byte[] salt, byte[] info, int length) {
        if (ikm == null) {
            throw new IllegalArgumentException("HKDF input keying material is missing");
        }
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        byte[] saltArg = (salt == null || salt.length == 0) ? null : salt;
        byte[] infoArg = info == null ? new byte[0] : info;
        hkdf.init(new HKDFParameters(ikm, saltArg, infoArg));
        byte[] out = new byte[length];
        hkdf.generateBytes(out, 0, length);
        return out;
    }
}
