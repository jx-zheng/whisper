package com.jxzheng.whisper.exceptions;

public class EncryptionException extends Exception {

    public EncryptionException(String message) {
        super(message);
    }

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
