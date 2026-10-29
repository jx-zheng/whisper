package com.jxzheng.whisper.schemes.permute;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Fisher–Yates visit order driven by a ChaCha20 keystream.
 * Matches the Whisper2 permutation: 32-byte seed, 12 zero nonce bytes,
 * counter 0, little-endian u32 words, rejection sampling.
 */
public final class KeyedPermutation {

    private KeyedPermutation() {
    }

    public static int[] order(int n, byte[] seed) {
        if (n < 0) {
            throw new IllegalArgumentException("permutation length is negative");
        }
        if (seed == null || seed.length != 32) {
            throw new IllegalArgumentException("permutation seed must be 32 bytes");
        }
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        if (n < 2) {
            return idx;
        }
        Cipher cipher;
        try {
            cipher = Cipher.getInstance("ChaCha20");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(seed, "ChaCha20"), new ChaCha20ParameterSpec(new byte[12], 0));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ChaCha20 is unavailable", e);
        }
        byte[] zero = new byte[4096];
        byte[] block = new byte[4096];
        int[] pos = {4096};
        for (int i = n - 1; i > 0; i--) {
            int j = below(cipher, zero, block, pos, i + 1);
            int tmp = idx[i];
            idx[i] = idx[j];
            idx[j] = tmp;
        }
        return idx;
    }

    private static int below(Cipher cipher, byte[] zero, byte[] block, int[] pos, int n) {
        if (n <= 1) {
            return 0;
        }
        long limit = (0xffff_ffffL / n) * n;
        while (true) {
            long v = next(cipher, zero, block, pos) & 0xffff_ffffL;
            if (v < limit) {
                return (int) (v % n);
            }
        }
    }

    private static int next(Cipher cipher, byte[] zero, byte[] block, int[] pos) {
        if (pos[0] + 4 > block.length) {
            try {
                int n = cipher.update(zero, 0, zero.length, block, 0);
                if (n != zero.length) {
                    throw new IllegalStateException("short ChaCha20 block");
                }
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("ChaCha20 keystream failed", e);
            }
            pos[0] = 0;
        }
        int p = pos[0];
        int v = (block[p] & 0xff)
                | ((block[p + 1] & 0xff) << 8)
                | ((block[p + 2] & 0xff) << 16)
                | ((block[p + 3] & 0xff) << 24);
        pos[0] = p + 4;
        return v;
    }
}
