package com.jxzheng.whisper.crypto;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * One Argon2id result per (role, profile, salt, secret) for a single reveal.
 * Close wipes every cached master.
 */
public final class KeyCache implements AutoCloseable {

    private final PasswordKdf kdf;
    private final Map<String, byte[]> masters = new HashMap<>();

    public KeyCache() {
        this(Argon2idKdf.INSTANCE);
    }

    public KeyCache(PasswordKdf kdf) {
        this.kdf = kdf;
    }

    /**
     * @return a copy of the 32-byte master. The caller wipes the copy.
     *         {@code pass} and {@code keyFile} are not wiped.
     */
    public byte[] master(String role, char[] pass, byte[] keyFile, KdfProfile profile, byte[] salt16) {
        if (salt16 == null || salt16.length != 16) {
            throw new IllegalArgumentException("salt must be 16 bytes");
        }
        byte[] secret = keyFile != null ? KeySchedule.normalizeKeyFile(keyFile) : Secrets.utf8(pass);
        try {
            String id = cacheId(role, profile, salt16, secret);
            byte[] hit = masters.get(id);
            if (hit != null) {
                return hit.clone();
            }
            byte[] derived = keyFile != null
                    ? secret.clone()
                    : kdf.derive(pass == null ? new char[0] : pass, salt16, profile);
            masters.put(id, derived);
            return derived.clone();
        } finally {
            Secrets.wipe(secret);
        }
    }

    @Override
    public void close() {
        for (byte[] master : masters.values()) {
            Secrets.wipe(master);
        }
        masters.clear();
    }

    private static String cacheId(String role, KdfProfile profile, byte[] salt, byte[] secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(role.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            sha.update((byte) profile.wire());
            sha.update(salt);
            sha.update(secret);
            return java.util.HexFormat.of().formatHex(sha.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean same(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }
}
