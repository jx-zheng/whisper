package com.jxzheng.whisper.crypto;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM, 128-bit tag, from the JDK. */
public final class AesGcmSuite implements AeadSuite {

    public static final AesGcmSuite INSTANCE = new AesGcmSuite();
    private static final int TAG_BITS = 128;

    private AesGcmSuite() {
    }

    @Override
    public String id() {
        return "aes-256-gcm";
    }

    @Override
    public String displayName() {
        return "AES-256-GCM";
    }

    @Override
    public int suiteBits() {
        return 1;
    }

    @Override
    public int overhead(int bodyLen) {
        return Stream.cipherLen(bodyLen) - Math.max(bodyLen, 0);
    }

    @Override
    public int seal(byte[] key32, byte[] nonce12, byte[] aad, byte[] in, int inOff, int inLen, byte[] out, int outOff)
            throws AuthException {
        return transform(Cipher.ENCRYPT_MODE, key32, nonce12, aad, in, inOff, inLen, out, outOff);
    }

    @Override
    public int open(byte[] key32, byte[] nonce12, byte[] aad, byte[] in, int inOff, int inLen, byte[] out, int outOff)
            throws AuthException {
        return transform(Cipher.DECRYPT_MODE, key32, nonce12, aad, in, inOff, inLen, out, outOff);
    }

    private static int transform(
            int mode, byte[] key32, byte[] nonce12, byte[] aad,
            byte[] in, int inOff, int inLen, byte[] out, int outOff) throws AuthException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key32, "AES"), new GCMParameterSpec(TAG_BITS, nonce12));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(in, inOff, inLen, out, outOff);
        } catch (GeneralSecurityException e) {
            throw new AuthException(AuthException.Kind.AUTH, 1, "AES-256-GCM rejected the chunk");
        }
    }
}
