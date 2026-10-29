package com.jxzheng.whisper.payload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class PayloadFrameTest {

    @Test
    void roundTripAndHashMismatchShowsNothing() throws Exception {
        byte[] content = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        PayloadFrame.Built built = PayloadFrame.build(PayloadKind.FILE, "../secret.txt", "text/plain", content, false);
        assertEquals("secret.txt", built.name());
        PayloadFrame.Parsed parsed = PayloadFrame.parse(built.bytes());
        assertArrayEquals(content, parsed.content());
        assertEquals("secret.txt", parsed.name());
        byte[] flipped = built.bytes().clone();
        flipped[flipped.length - 1] ^= 1;
        PayloadFormatException error = assertThrows(PayloadFormatException.class, () -> PayloadFrame.parse(flipped));
        assertEquals(PayloadFormatException.Kind.HASH, error.kind());
    }

    @Test
    void unsafeNamesAreBasenames() {
        assertEquals("report.pdf", NameSanitizer.sanitize("../a/report.pdf", "application/pdf"));
        assertEquals("whisper-payload.bin", NameSanitizer.sanitize("CON.txt", "application/octet-stream"));
        assertEquals("whisper-payload.txt", NameSanitizer.sanitize("..", "text/plain"));
        assertEquals("whisper-payload.png", NameSanitizer.sanitize("///", "image/png"));
    }

    @Test
    void fuzzedFramesThrowOnlyFormatErrors() {
        Random random = new Random(0x5eed);
        PayloadFrame.Built built = PayloadFrame.build(
                PayloadKind.IMAGE, "photo.png", "image/png", new byte[] {1, 2, 3, 4, 5}, true);
        for (int i = 0; i < 10_000; i++) {
            byte[] sample = i % 2 == 0 ? built.bytes().clone() : new byte[20 + random.nextInt(80)];
            int flips = 1 + random.nextInt(4);
            for (int f = 0; f < flips; f++) {
                sample[random.nextInt(sample.length)] ^= (byte) (1 + random.nextInt(255));
            }
            try {
                PayloadFrame.parse(sample);
            } catch (PayloadFormatException expected) {
                assertTrue(expected.kind() == PayloadFormatException.Kind.FORMAT
                        || expected.kind() == PayloadFormatException.Kind.HASH);
            }
        }
    }
}
