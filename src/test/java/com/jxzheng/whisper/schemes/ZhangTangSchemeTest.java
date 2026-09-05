package com.jxzheng.whisper.schemes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.jxzheng.whisper.exceptions.CorruptOrInvalidStegoImageException;
import com.jxzheng.whisper.exceptions.MessageTooLongException;

class ZhangTangSchemeTest {

    @Test
    void calculateNewColorEnforcesParity() {
        // prev LSB=1, curr LSB=0 => (1+0)%2=1; want msgBit=0 => adjust by -1
        assertEquals(99, ZhangTangScheme.calculateNewColor(100, 101, 0));
        // already matches => unchanged
        assertEquals(100, ZhangTangScheme.calculateNewColor(100, 101, 1));
    }

    @Test
    void calculateNewColorHandlesOverflow() {
        // prev LSB=0, curr=255 LSB=1 => (0+1)%2=1; want 0 => 255-1=254
        assertEquals(254, ZhangTangScheme.calculateNewColor(255, 0, 0));
        // prev LSB=1, curr=0 LSB=0 => (1+0)%2=1; want 0 => 0-1=-1 => +2 => 1
        assertEquals(1, ZhangTangScheme.calculateNewColor(0, 1, 0));
    }

    @Test
    void getNthBitReadsMsbFirst() {
        byte[] bytes = {(byte) 0b1010_0000};
        assertEquals(1, ZhangTangScheme.getNthBit(bytes, 0));
        assertEquals(0, ZhangTangScheme.getNthBit(bytes, 1));
        assertEquals(1, ZhangTangScheme.getNthBit(bytes, 2));
    }

    @Test
    void pixelsNeededForBytesRoundsUp() {
        assertEquals(0, ZhangTangScheme.pixelsNeededForBytes(0));
        assertEquals(3, ZhangTangScheme.pixelsNeededForBytes(1)); // 8 bits / 3 ≈ 2.67 → 3
        assertEquals(8, ZhangTangScheme.pixelsNeededForBytes(3)); // 24 bits / 3 = 8
    }

    @Test
    void roundTripEmbedExtract() {
        BufferedImage cover = randomImage(64, 64, 42);
        byte[] message = "Zhang-Tang works!".getBytes(StandardCharsets.UTF_8);

        BufferedImage stego = new ZhangTangScheme(cover, "demo-key").embedMessage(message);
        byte[] recovered = new ZhangTangScheme(stego, "demo-key").extractMessage();

        assertArrayEquals(message, recovered);
    }

    @Test
    void roundTripWithBinaryPayload() {
        BufferedImage cover = randomImage(128, 128, 7);
        byte[] message = new byte[200];
        new Random(99).nextBytes(message);

        BufferedImage stego = new ZhangTangScheme(cover, "binary-key").embedMessage(message);
        byte[] recovered = new ZhangTangScheme(stego, "binary-key").extractMessage();

        assertArrayEquals(message, recovered);
    }

    @Test
    void wrongKeyFailsExtraction() {
        BufferedImage cover = randomImage(64, 64, 1);
        BufferedImage stego = new ZhangTangScheme(cover, "right-key")
                .embedMessage("secret".getBytes(StandardCharsets.UTF_8));

        assertThrows(CorruptOrInvalidStegoImageException.class,
                () -> new ZhangTangScheme(stego, "wrong-key").extractMessage());
    }

    @Test
    void rejectsOversizedMessageForTinyImage() {
        BufferedImage cover = randomImage(4, 4, 3);
        byte[] huge = new byte[100];
        assertThrows(MessageTooLongException.class,
                () -> new ZhangTangScheme(cover, "key").embedMessage(huge));
    }

    private static BufferedImage randomImage(int width, int height, long seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = random.nextInt(0x1000000);
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }
}
