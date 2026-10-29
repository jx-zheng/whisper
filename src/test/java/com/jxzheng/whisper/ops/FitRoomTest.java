package com.jxzheng.whisper.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jxzheng.whisper.crypto.Envelope;
import com.jxzheng.whisper.crypto.KdfProfile;
import com.jxzheng.whisper.exceptions.MessageTooLongException;
import com.jxzheng.whisper.payload.ImageFit;
import com.jxzheng.whisper.payload.PayloadFrame;
import com.jxzheng.whisper.payload.PayloadKind;
import com.jxzheng.whisper.schemes.RatePreset;

class FitRoomTest {

    static {
        System.setProperty("whisper.kdf.test", "true");
    }

    @Test
    void sourceMimeRoomIsTooLargeForTheJpegFrame(@TempDir Path dir) throws Exception {
        byte[] cover = noisePng(320, 240, 4);
        Path coverPath = dir.resolve("cover.png");
        Files.write(coverPath, cover);
        String sourceName = "secret.png";
        int sourceRoom = PayloadOps.shownCapacity(
                cover, "hill-stc", RatePreset.STANDARD, sourceName, "image/png");
        int fitRoom = PayloadOps.fitRoom(cover, "hill-stc", RatePreset.STANDARD, sourceName);
        String fittedName = PayloadFrame.storedName(PayloadKind.IMAGE, sourceName, "image/jpeg", true);
        int extra = fittedName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + "image/jpeg".length()
                - sourceName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                - "image/png".length();
        assertTrue(extra > 0);
        assertEquals(sourceRoom - extra, fitRoom);
        assertTrue(fitRoom > 200);

        byte[] gap = new byte[sourceRoom];
        new Random(1).nextBytes(gap);
        gap[0] = (byte) 0xff;
        gap[1] = (byte) 0xd8;
        gap[2] = (byte) 0xff;
        MessageTooLongException tooBig = assertThrows(MessageTooLongException.class,
                () -> hide(coverPath, dir.resolve("gap.png"), gap, sourceName));
        assertTrue(tooBig.getMessage().contains("Envelope is"));

        byte[] photo = noisePng(16, 12, 9);
        ImageFit.Result fitted = ImageFit.fit(photo, 75, 16, fitRoom);
        assertTrue(fitted.bytes().length <= fitRoom);
        PayloadOps.Outcome outcome = hide(coverPath, dir.resolve("stego.png"), fitted.bytes(), sourceName);
        assertTrue(outcome.ok());
        assertEquals(fitted.bytes().length, outcome.content().length);
        assertTrue(java.util.Arrays.equals(fitted.bytes(), outcome.content()));
    }

    private static PayloadOps.Outcome hide(Path cover, Path output, byte[] content, String sourceName) throws Exception {
        return new PayloadOps().hide(new PayloadOps.HideRequest(
                cover, output, PayloadKind.IMAGE, content, sourceName, "image/jpeg",
                "hill-stc", Envelope.SuiteKind.CHACHA, RatePreset.STANDARD, KdfProfile.TEST,
                "walk".toCharArray(), null, false, false, true));
    }

    private static byte[] noisePng(int width, int height, int seed) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IllegalStateException("no png writer");
        }
        return out.toByteArray();
    }
}
