package com.jxzheng.whisper.crypto;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** ChaCha20-Poly1305 (RFC 8439) from the JDK. Default suite. */
public final class ChaCha20Poly1305Suite implements AeadSuite {

    public static final ChaCha20Poly1305Suite INSTANCE = new ChaCha20Poly1305Suite();

    private ChaCha20Poly1305Suite() {
    }

    @Override
    public String id() {
        return "chacha20-poly1305";
    }

    @Override
    public String displayName() {
        return "ChaCha20-Poly1305 (recommended)";
    }

    @Override
    public int suiteBits() {
        return 0;
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
            Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
            cipher.init(mode, new SecretKeySpec(key32, "ChaCha20"), new IvParameterSpec(nonce12));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(in, inOff, inLen, out, outOff);
        } catch (GeneralSecurityException e) {
            throw new AuthException(AuthException.Kind.AUTH, 0, "ChaCha20-Poly1305 rejected the chunk");
        }
    }
}
