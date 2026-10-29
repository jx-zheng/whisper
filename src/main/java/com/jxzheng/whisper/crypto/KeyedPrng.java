package com.jxzheng.whisper.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.Random;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Deterministic keyed PRNG for stego pixel selection.
 *
 * <p>Passphrase material is stretched with PBKDF2-HMAC-SHA256 (fixed domain salt),
 * then expanded via HMAC-SHA256 in counter mode with a labeled input. The same
 * passphrase always replays the same walk; distinct passphrases diverge quickly.
 * This replaces both {@code Random(key.hashCode())} and a bare SHA-256 seed.
 *
 * <p>An empty passphrase is the public default walk: the same KDF with an empty
 * password. It is deterministic and not a secret. The derived key is cached
 * because every embed and extract with a blank stego passphrase uses it.
 */
public final class KeyedPrng extends Random {

    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final byte[] KDF_SALT =
            "whisper-stego-prng-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DOMAIN =
            "whisper-prng".getBytes(StandardCharsets.UTF_8);
    private static final int PBKDF2_ITERATIONS = 65_536;
    private static final int MAC_KEY_BITS = 256;
    private static final int BLOCK_BYTES = 32;

    private final byte[] macKey;
    private long counter;
    private byte[] block = new byte[0];
    private int blockOffset;

    private KeyedPrng(byte[] macKey) {
        super(0L);
        this.macKey = macKey;
        this.counter = 0L;
        this.blockOffset = BLOCK_BYTES;
    }

    public static KeyedPrng fromPassphrase(String passphrase) {
        Objects.requireNonNull(passphrase, "passphrase");
        return new KeyedPrng(stretch(passphrase));
    }

    /**
     * Key for the length-header HMAC.
     *
     * <p>A non-empty passphrase is used as raw UTF-8 bytes, matching earlier builds.
     * HMAC-SHA256 rejects a zero-length key, so the empty passphrase uses the
     * PBKDF2 output of the public default walk instead of the empty byte string.
     */
    public static byte[] headerMacKey(String passphrase) {
        Objects.requireNonNull(passphrase, "passphrase");
        if (passphrase.isEmpty()) {
            return EmptyMacKey.BYTES.clone();
        }
        return passphrase.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] stretch(String passphrase) {
        if (passphrase.isEmpty()) {
            return EmptyMacKey.BYTES.clone();
        }
        return pbkdf2(passphrase);
    }

    private static byte[] pbkdf2(String passphrase) {
        PBEKeySpec spec = new PBEKeySpec(
                passphrase.toCharArray(), KDF_SALT, PBKDF2_ITERATIONS, MAC_KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(KDF_ALGORITHM)
                    .generateSecret(spec)
                    .getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2-HMAC-SHA256 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** Lazy so a non-empty passphrase does not pay the empty-key stretch. */
    private static final class EmptyMacKey {
        static final byte[] BYTES = pbkdf2("");
    }

    @Override
    protected synchronized int next(int bits) {
        int value = (nextByte() << 24)
                | (nextByte() << 16)
                | (nextByte() << 8)
                | nextByte();
        return value >>> (32 - bits);
    }

    @Override
    public synchronized void setSeed(long seed) {
        // Ignore Random's constructor/setSeed calls; seeding is passphrase-only.
    }

    private int nextByte() {
        if (blockOffset >= block.length) {
            block = nextBlock();
            blockOffset = 0;
        }
        return block[blockOffset++] & 0xFF;
    }

    private byte[] nextBlock() {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(macKey, MAC_ALGORITHM));
            mac.update(DOMAIN);
            mac.update(ByteBuffer.allocate(Long.BYTES).putLong(counter++).array());
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
