package com.jxzheng.whisper.encryption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Ignore;
import org.junit.Test;

import com.jxzheng.whisper.exceptions.EncryptionException;

public class AesEncryptorTest {
    
    // TODO: fix tests
    
    @Ignore
    @Test
    public void test_Encryption() {
        AesCipher aesCipher = new AesCipher("TEST STRING");
        String ciphertext;
        try {
            ciphertext = aesCipher.encrypt("password");
        } catch (EncryptionException e) {
            fail("Encountered exception while encrypting: " + e.getMessage());
            return;
        }
        assertEquals("result", ciphertext);
    }

    @Ignore
    @Test
    public void test_Decryption() {
        AesCipher aesCipher = new AesCipher("");
        String plaintext;
        try {
            plaintext = aesCipher.decrypt("password");
        } catch (EncryptionException e) {
            fail("Encountered exception while decrypting: " + e.getMessage());
            return;
        }
        assertEquals("result", plaintext);
    }

}
