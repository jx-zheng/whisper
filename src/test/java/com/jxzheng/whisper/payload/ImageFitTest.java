package com.jxzheng.whisper.payload;

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
        ImageFit.Result loose = ImageFit.fit(original, 85, 240, 5_000_000, "photo.png");
        int looseFrame = PayloadFrame.build(
                PayloadKind.IMAGE, "photo.png", "image/jpeg", loose.bytes(), true).bytes().length;
        assertTrue(looseFrame <= 5_000_000);
        assertTrue(loose.width() >= 200);

        int tight = Math.max(800, looseFrame / 4);
        ImageFit.Result fitted = ImageFit.fit(original, 85, 240, tight, "photo.png");
        int frame = PayloadFrame.build(
                PayloadKind.IMAGE, "photo.png", "image/jpeg", fitted.bytes(), true).bytes().length;
        assertTrue(frame <= tight, "frame " + frame + " capacity " + tight);
        assertTrue(fitted.bytes().length < loose.bytes().length || fitted.width() < loose.width());
    }

    @Test
    void reportsWhenItNeverFits() {
        byte[] original = noisePng(64, 64, 3);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> ImageFit.fit(original, 75, 64, 1, "photo.png"));
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
