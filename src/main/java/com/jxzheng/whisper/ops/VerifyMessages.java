package com.jxzheng.whisper.ops;

/**
 * User-facing verification wording. Failures never include payload bytes.
 */
public final class VerifyMessages {

    public static final String NOTHING_FOUND =
            "Nothing found with this key. Either the stego key is wrong, the image has no Whisper payload, "
                    + "or it was edited/recompressed after embedding. "
                    + "Recompression, resizing, chat apps and screenshots destroy hidden data.";
    public static final String WRONG_ENCRYPTION =
            "Wrong encryption passphrase, or the payload was tampered with.";
    public static final String INCOMPLETE = "Payload damaged (incomplete).";
    public static final String DECOMPRESS = "Payload damaged (decompression failed).";
    public static final String HASH = "Integrity check failed: content hash does not match.";
    public static final String DAMAGED = "Payload damaged.";
    public static final String LEGACY = "Unverified (legacy format — re-embed to get verification)";
    public static final String LEGACY_ENCRYPTED =
            "Unverified (legacy format). AES-GCM tag OK; no content hash";

    private VerifyMessages() {
    }

    public static String verified(String cipher, String sha256) {
        return "Verified · " + cipher + " · SHA-256 " + sha256;
    }
}
