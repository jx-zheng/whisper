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
        return derive(pass, salt16, profile, false);
    }

    /** Reveal of a high-profile envelope needs a larger heap, not a different profile flag. */
    public byte[] deriveReveal(char[] pass, byte[] salt16, KdfProfile profile) {
        return derive(pass, salt16, profile, true);
    }

    private byte[] derive(char[] pass, byte[] salt16, KdfProfile profile, boolean reveal) {
        if (salt16 == null || salt16.length != 16) {
            throw new IllegalArgumentException("Argon2id salt must be 16 bytes");
        }
        if (profile == null) {
            throw new IllegalArgumentException("Argon2id needs a password profile");
        }
        byte[] password = Secrets.utf8(pass);
        try {
            return deriveBytes(password, salt16, null, null, profile.iterations(), profile.memoryKiB(), profile.lanes());
        } catch (OutOfMemoryError e) {
            String message = reveal
                    ? "Out of memory. The sender used the high Argon2id profile. Rerun with more heap, for example JAVA_TOOL_OPTIONS=-Xmx3g."
                    : "Out of memory. The high Argon2id profile needs about 2 GiB of RAM. Use --kdf default.";
            throw new IllegalArgumentException(message, e);
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
