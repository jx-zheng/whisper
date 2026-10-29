package com.jxzheng.whisper.payload;

/** The authenticated frame is not a usable payload. Content is not returned. */
public final class PayloadFormatException extends Exception {

    public enum Kind {
        FORMAT,
        HASH
    }

    private final Kind kind;

    public PayloadFormatException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
