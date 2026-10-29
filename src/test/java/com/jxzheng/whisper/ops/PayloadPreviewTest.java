package com.jxzheng.whisper.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PayloadPreviewTest {

    @Test
    void showsUtf8Text() {
        PayloadPreview.View view = PayloadPreview.of("hello\nthere".getBytes(StandardCharsets.UTF_8));
        assertTrue(view.text());
        assertEquals("hello\nthere", view.content());
    }

    @Test
    void emptyPayloadIsText() {
        PayloadPreview.View view = PayloadPreview.of(new byte[0]);
        assertTrue(view.text());
        assertEquals("", view.content());
    }

    @Test
    void binaryPayloadIsHex() {
        PayloadPreview.View view = PayloadPreview.of(new byte[] {0x00, 0x01, (byte) 0xFF});
        assertFalse(view.text());
        assertTrue(view.content().startsWith("0000  "));
        assertTrue(view.content().contains("ff"));
    }
}
