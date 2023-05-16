package com.jxzheng.whisper.encryption;

import java.nio.charset.StandardCharsets;

import com.jxzheng.whisper.exceptions.EncryptionException;

public abstract class AbstractCipher {
    private byte[] text;

    public AbstractCipher(byte[] text) {
        this.text = text;
    }

    public AbstractCipher(String text) {
        this.text = text.getBytes(StandardCharsets.US_ASCII);
    }

    public abstract String encrypt(String key) throws EncryptionException;

    public abstract String decrypt(String key) throws EncryptionException;

    public byte[] getText() {
        return this.text;
    }
}
