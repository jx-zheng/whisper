package com.jxzheng.whisper.exceptions;

public class MessageTooLongException extends RuntimeException {

    public MessageTooLongException(String message) {
        super(message);
    }
}
