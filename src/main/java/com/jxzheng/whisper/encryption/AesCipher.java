package com.jxzheng.whisper.encryption;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.jxzheng.whisper.exceptions.EncryptionException;

public class AesCipher extends AbstractCipher {
    private final static int AES_BYTES = 16;

    public AesCipher(byte[] text) {
        super(text);
    }

    public AesCipher(String text) {
        super(text);
    }

    @Override
    public String encrypt(String key) throws EncryptionException {
        Cipher aesCipher = getCipher(key, Cipher.ENCRYPT_MODE);
        return applyCipher(aesCipher, Cipher.ENCRYPT_MODE);
    }

    @Override
    public String decrypt(String key) throws EncryptionException {
        Cipher aesCipher = getCipher(key, Cipher.DECRYPT_MODE);
        return applyCipher(aesCipher, Cipher.DECRYPT_MODE);
    }

    private Cipher getCipher(String key, int cipherMode) throws EncryptionException {
        SecretKey secretKey = generateSecretKey(key);
        Cipher aesCipher;
        try {
            aesCipher = Cipher.getInstance("AES/ECB/NoPadding");
            aesCipher.init(cipherMode, secretKey);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException e) {
            System.out.println(e);
            throw new EncryptionException("Failed to initialize AES cipher instance");
        }
        return aesCipher;
    }

    private String applyCipher(Cipher aesCipher, int cipherMode) throws EncryptionException {
        byte[] resultText;
        byte[] inputText;
        final int rawInputLength = this.getText().length;
        if (cipherMode == Cipher.ENCRYPT_MODE && (rawInputLength % 16 != 0)) {
            final int newInputLength = ((int) Math.ceil(rawInputLength / 16.0)) * 16;
            inputText = new byte[newInputLength];
            System.arraycopy(this.getText(), 0, inputText, 0, rawInputLength);
        } else {
            inputText = this.getText();
        }
        try {
            resultText = aesCipher.doFinal(inputText);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            System.out.println(e);
            throw new EncryptionException("Failed to apply AES cipher");
        }
        return new String(resultText, StandardCharsets.US_ASCII);
    }

    // TODO: use proper key derivation function
    private SecretKeySpec generateSecretKey(String key) {
        byte[] rawKey = key.getBytes();
        byte[] resizedKey;
        if (key.length() != 16) {
            resizedKey = Arrays.copyOf(rawKey, AES_BYTES);
        } else {
            resizedKey = rawKey;
        }
        return new SecretKeySpec(resizedKey, "AES");
    }

}
