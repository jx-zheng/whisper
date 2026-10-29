package com.jxzheng.whisper.crypto;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Argon2id (RFC 9106) via Bouncy Castle's low-level API.
 * Bouncy Castle is not installed as a JCA provider.
 */
public final class Argon2idKdf implements PasswordKdf {

    public static final Argon2idKdf INSTANCE = new Argon2idKdf();

    private Argon2idKdf() {
    }

    @Override
    public byte[] derive(char[] pass, byte[] salt16, KdfProfile profile) {
        if (salt16 == null || salt16.length != 16) {
            throw new IllegalArgumentException("Argon2id salt must be 16 bytes");
        }
        if (profile == null || profile == KdfProfile.KEYFILE) {
            throw new IllegalArgumentException("Argon2id needs a password profile");
        }
        byte[] password = Secrets.utf8(pass);
        try {
            return deriveBytes(password, salt16, null, null, profile.iterations(), profile.memoryKiB(), profile.lanes());
        } finally {
            Secrets.wipe(password);
        }
    }

    /**
     * Raw Argon2id, including the RFC 9106 secret and associated-data inputs.
     * Used by the RFC vector test. {@code password} is wiped by the caller.
     */
    public static byte[] deriveBytes(
            byte[] password, byte[] salt, byte[] secret, byte[] associated,
            int iterations, int memoryKiB, int lanes) {
        Argon2Parameters.Builder builder = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(iterations)
                .withMemoryAsKB(memoryKiB)
                .withParallelism(lanes)
                .withSalt(salt);
        if (secret != null) {
            builder.withSecret(secret);
        }
        if (associated != null) {
            builder.withAdditional(associated);
        }
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(builder.build());
        byte[] out = new byte[32];
        generator.generateBytes(password, out, 0, out.length);
        return out;
    }
}
