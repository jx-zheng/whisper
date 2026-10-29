package com.jxzheng.whisper.payload;

/** What the authenticated frame is carrying. */
public enum PayloadKind {
    TEXT(0),
    FILE(1),
    IMAGE(2);

    private final int wire;

    PayloadKind(int wire) {
        this.wire = wire;
    }

    public int wire() {
        return wire;
    }

    public static PayloadKind fromWire(int wire) {
        for (PayloadKind kind : values()) {
            if (kind.wire == wire) {
                return kind;
            }
        }
        return null;
    }
}
