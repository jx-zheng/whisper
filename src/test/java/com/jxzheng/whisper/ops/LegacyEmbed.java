package com.jxzheng.whisper.ops;

import java.awt.image.BufferedImage;

import com.jxzheng.whisper.exceptions.EncryptionException;

/** Test-only Zhang–Tang embed. Production code does not create 0.9 payloads. */
public final class LegacyEmbed {

    private LegacyEmbed() {
    }

    public static StegoOperations.EmbedResult embed(
            BufferedImage cover,
            byte[] message,
            String stegoKey,
            boolean encrypt,
            String encryptKey) throws EncryptionException {
        return new StegoOperations().embed(cover, message, stegoKey, encrypt, encryptKey, "zhang-tang", "aes");
    }
}
