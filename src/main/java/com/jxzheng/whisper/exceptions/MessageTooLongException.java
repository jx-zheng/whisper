package com.jxzheng.whisper.exceptions;

public class MessageTooLongException extends RuntimeException {
    public MessageTooLongException() {
        super();
    }

    public MessageTooLongException(String cause) {
        super(cause);
    }
 
 }
