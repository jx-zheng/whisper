package com.jxzheng.whisper.crypto;

import java.util.List;

/**
 * Cipher ids for new embeds. Blank means ChaCha20-Poly1305.
 * AES-128-GCM ({@code aes}) is decrypt-only and is not offered here.
 */
public final class CipherRegistry {

    public static final String CHACHA = "chacha20-poly1305";
    public static final String AES_256 = "aes-256-gcm";
    public static final String NONE = "none";

    private static final CipherRegistry INSTANCE = new CipherRegistry(List.of(CHACHA, AES_256, NONE));

    private final List<String> ids;

    public CipherRegistry(List<String> ids) {
        this.ids = List.copyOf(ids);
    }

    public static CipherRegistry shared() {
        return INSTANCE;
    }

    public List<String> ids() {
        return ids;
    }

    public String canonical(String name) {
        String id = (name == null || name.isBlank()) ? CHACHA : name.toLowerCase(java.util.Locale.ROOT).trim();
        if (!ids.contains(id)) {
            throw new IllegalArgumentException("Unsupported cipher: " + name);
        }
        return id;
    }
}
