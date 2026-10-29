package com.jxzheng.whisper.crypto;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Argon2id then HKDF-SHA-256, with the split-key schedule from the crypto port.
 */
public final class KeySchedule {

    public static final String INFO_STEGO = "whisper v2 stego";
    public static final String INFO_AEAD = "whisper v2 aead";
    public static final String INFO_PAYLOAD = "whisper v2 payload";
    static final byte[] ENC_SALT_LABEL = "whisper v2 enc salt".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private final PasswordKdf kdf;

    public KeySchedule() {
        this(Argon2idKdf.INSTANCE);
    }

    public KeySchedule(PasswordKdf kdf) {
        this.kdf = kdf;
    }

    /**
     * Secrets the caller must {@link Derived#close()}.
     * Passphrases are wiped.
     */
    public Derived derive(
            char[] stegoPass, char[] encPass, boolean split,
            KdfProfile profile, byte[] salt16, byte[] fileNonce) {
        if (salt16 == null || salt16.length != 16) {
            throw new IllegalArgumentException("salt must be 16 bytes");
        }
        if (fileNonce == null || fileNonce.length != 16) {
            throw new IllegalArgumentException("file nonce must be 16 bytes");
        }
        byte[] stegoMaster = null;
        byte[] encMaster = null;
        try {
            if (stegoPass == null) {
                throw new IllegalArgumentException("stego passphrase is required");
            }
            stegoMaster = kdf.derive(stegoPass, salt16, profile);
            if (!split) {
                encMaster = Secrets.copy(stegoMaster);
            } else {
                if (encPass == null) {
                    throw new IllegalArgumentException("encryption passphrase is required");
                }
                byte[] encSalt = encSalt(salt16);
                try {
                    encMaster = kdf.derive(encPass, encSalt, profile);
                } finally {
                    Secrets.wipe(encSalt);
                }
            }
            byte[] visit = Hkdf.expand(stegoMaster, null, INFO_STEGO, 32);
            byte[] aead = Hkdf.expand(encMaster, null, INFO_AEAD, 32);
            byte[] payload = Hkdf.expand(aead, fileNonce, INFO_PAYLOAD, 32);
            Secrets.wipe(aead);
            return new Derived(visit, payload, stegoMaster);
        } catch (RuntimeException e) {
            Secrets.wipe(stegoMaster);
            throw e;
        } finally {
            Secrets.wipe(encMaster);
            Secrets.wipe(stegoPass);
            Secrets.wipe(encPass);
        }
    }

    /** First 16 bytes of SHA-256("whisper v2 enc salt" || salt). Caller wipes. */
    public static byte[] encSalt(byte[] salt16) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(ENC_SALT_LABEL);
            sha.update(salt16);
            byte[] full = sha.digest();
            byte[] salt = Arrays.copyOf(full, 16);
            Secrets.wipe(full);
            return salt;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }

    /** Visit seed and payload key. Close wipes them. */
    public static final class Derived implements AutoCloseable {
        private final byte[] visitSeed;
        private final byte[] payloadKey;
        private final byte[] stegoMaster;

        Derived(byte[] visitSeed, byte[] payloadKey, byte[] stegoMaster) {
            this.visitSeed = visitSeed;
            this.payloadKey = payloadKey;
            this.stegoMaster = stegoMaster;
        }

        public byte[] visitSeed() {
            return visitSeed;
        }

        public byte[] payloadKey() {
            return payloadKey;
        }

        public byte[] stegoMaster() {
            return stegoMaster;
        }

        @Override
        public void close() {
            Secrets.wipe(visitSeed);
            Secrets.wipe(payloadKey);
            Secrets.wipe(stegoMaster);
        }
    }
}
