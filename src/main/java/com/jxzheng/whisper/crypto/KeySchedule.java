package com.jxzheng.whisper.crypto;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Argon2id (or a key file) then HKDF-SHA-256, with the split-key schedule
 * from the crypto port.
 */
public final class KeySchedule {

    public static final String INFO_STEGO = "whisper v2 stego";
    public static final String INFO_AEAD = "whisper v2 aead";
    public static final String INFO_PAYLOAD = "whisper v2 payload";
    public static final String INFO_MAC = "whisper v2 mac";
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
     * Passphrases and {@code keyFile} are wiped.
     */
    public Derived derive(
            char[] stegoPass, char[] encPass, boolean split, byte[] keyFile,
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
            if (keyFile != null) {
                stegoMaster = normalizeKeyFile(keyFile);
                Secrets.wipe(keyFile);
                encMaster = Secrets.copy(stegoMaster);
                split = false;
            } else {
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
            }
            byte[] visit = Hkdf.expand(stegoMaster, null, INFO_STEGO, 32);
            byte[] aead = Hkdf.expand(encMaster, null, INFO_AEAD, 32);
            byte[] payload = Hkdf.expand(aead, fileNonce, INFO_PAYLOAD, 32);
            byte[] mac = Hkdf.expand(encMaster, fileNonce, INFO_MAC, 32);
            Secrets.wipe(aead);
            return new Derived(visit, payload, mac, stegoMaster);
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

    public static byte[] normalizeKeyFile(byte[] raw) {
        if (raw == null) {
            throw new IllegalArgumentException("key file is missing");
        }
        byte[] b = raw;
        if (b.length == 33 && b[32] == '\n') {
            b = Arrays.copyOf(b, 32);
        } else if (b.length == 34 && b[32] == '\r' && b[33] == '\n') {
            b = Arrays.copyOf(b, 32);
        }
        if (b.length == 32 && b == raw) {
            return raw.clone();
        }
        if (b.length == 32) {
            return b;
        }
        int start = 0;
        int end = b.length;
        while (start < end && isWs(b[start])) {
            start++;
        }
        while (end > start && isWs(b[end - 1])) {
            end--;
        }
        int n = end - start;
        try {
            if (n == 64 && isHex(b, start, end)) {
                return HexFormat.of().parseHex(new String(b, start, n, java.nio.charset.StandardCharsets.US_ASCII));
            }
            if (n == 44) {
                byte[] decoded = Base64.getDecoder().decode(new String(b, start, n, java.nio.charset.StandardCharsets.US_ASCII));
                if (decoded.length != 32) {
                    Secrets.wipe(decoded);
                    throw keyFile();
                }
                return decoded;
            }
        } catch (IllegalArgumentException e) {
            throw keyFile();
        }
        throw keyFile();
    }

    private static IllegalArgumentException keyFile() {
        return new IllegalArgumentException(
                "key file must be 32 raw bytes, 64 hex characters, or 44 base64 characters");
    }

    private static boolean isWs(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t';
    }

    private static boolean isHex(byte[] b, int start, int end) {
        for (int i = start; i < end; i++) {
            int c = b[i] & 0xff;
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** Visit seed, payload key, and MAC key. Close wipes them. */
    public static final class Derived implements AutoCloseable {
        private final byte[] visitSeed;
        private final byte[] payloadKey;
        private final byte[] macKey;
        private final byte[] stegoMaster;

        Derived(byte[] visitSeed, byte[] payloadKey, byte[] macKey, byte[] stegoMaster) {
            this.visitSeed = visitSeed;
            this.payloadKey = payloadKey;
            this.macKey = macKey;
            this.stegoMaster = stegoMaster;
        }

        public byte[] visitSeed() {
            return visitSeed;
        }

        public byte[] payloadKey() {
            return payloadKey;
        }

        public byte[] macKey() {
            return macKey;
        }

        public byte[] stegoMaster() {
            return stegoMaster;
        }

        @Override
        public void close() {
            Secrets.wipe(visitSeed);
            Secrets.wipe(payloadKey);
            Secrets.wipe(macKey);
            Secrets.wipe(stegoMaster);
        }
    }
}
