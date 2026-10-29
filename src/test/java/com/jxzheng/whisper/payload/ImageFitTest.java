package com.jxzheng.whisper.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class ImageFitTest {

    @Test
    void stepsDownUntilFrameFits() throws Exception {
        byte[] original = noisePng(240, 180, 7);
        ImageFit.Result loose = ImageFit.fit(original, 85, 240, 5_000_000);
        assertTrue(loose.bytes().length <= 5_000_000);
        assertTrue(loose.width() >= 200);

        int tight = Math.max(800, loose.bytes().length / 4);
        ImageFit.Result fitted = ImageFit.fit(original, 85, 240, tight);
        assertTrue(fitted.bytes().length <= tight, "image " + fitted.bytes().length + " capacity " + tight);
        assertTrue(fitted.bytes().length < loose.bytes().length || fitted.width() < loose.width());
    }

    @Test
    void contentRoomIsNotChargedTheFrameHeaderAgain() throws Exception {
        byte[] original = noisePng(96, 64, 5);
        ImageFit.Result full = ImageFit.fit(original, 80, 96, Integer.MAX_VALUE);
        int content = full.bytes().length;
        int framed = PayloadFrame.build(
                PayloadKind.IMAGE, "photo.png", "image/jpeg", full.bytes(), true).bytes().length;
        assertTrue(framed > content + 40, "header is " + (framed - content));

        ImageFit.Result fitted = ImageFit.fit(original, 80, 96, content);
        assertEquals(full.width(), fitted.width());
        assertEquals(full.height(), fitted.height());
        assertTrue(fitted.bytes().length <= content);
        int fittedFrame = PayloadFrame.build(
                PayloadKind.IMAGE, "photo.png", "image/jpeg", fitted.bytes(), true).bytes().length;
        assertTrue(fittedFrame > content, "framed payload " + fittedFrame + " exceeds content room " + content);
    }

    @Test
    void reportsWhenItNeverFits() {
        byte[] original = noisePng(64, 64, 3);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> ImageFit.fit(original, 75, 64, 1));
        assertTrue(error.getMessage().contains("does not fit"));
        assertTrue(error.getMessage().contains("capacity is 1"));
    }

    private static byte[] noisePng(int width, int height, int seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("no png writer");
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
