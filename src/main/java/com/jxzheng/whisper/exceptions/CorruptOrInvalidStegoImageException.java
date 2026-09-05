package com.jxzheng.whisper.exceptions;

public class CorruptOrInvalidStegoImageException extends RuntimeException {

    public CorruptOrInvalidStegoImageException(String message) {
        super(message);
    }

    public CorruptOrInvalidStegoImageException(String message, Throwable cause) {
        super(message, cause);
    }
}
