package com.jxzheng.whisper.crypto;

/**
 * Password-based KDF. The only implementation is Argon2id.
 * {@code pass} is not wiped here; the caller owns it.
 */
public interface PasswordKdf {

    /** 32-byte secret. The caller wipes it. */
    byte[] derive(char[] pass, byte[] salt16, KdfProfile profile);

    /** Same as {@link #derive}, with the reveal out-of-memory wording. */
    default byte[] deriveReveal(char[] pass, byte[] salt16, KdfProfile profile) {
        return derive(pass, salt16, profile);
    }
}
