package com.jxzheng.whisper.ops;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jxzheng.whisper.encryption.AesCipher;
import com.jxzheng.whisper.exceptions.CorruptOrInvalidStegoImageException;
import com.jxzheng.whisper.exceptions.EncryptionException;
import com.jxzheng.whisper.exceptions.MessageTooLongException;
import com.jxzheng.whisper.schemes.AbstractScheme;

class StegoOperationsTest {

    private final StegoOperations operations = new StegoOperations();

    @Test
    void roundTripAndSchemeAliases() throws Exception {
        BufferedImage cover = noise(48, 48, 3);
        byte[] message = "alias".getBytes(StandardCharsets.UTF_8);

        BufferedImage stego = operations.embed(cover, message, "key", false, null, "ZT", "aes").image();
        byte[] recovered = operations.extract(stego, "key", false, null, "zhangtang", "aes");

        assertArrayEquals(message, recovered);
    }

    @Test
    void encryptedRoundTripReportsOverhead() throws Exception {
        BufferedImage cover = noise(64, 64, 9);
        byte[] message = "secret".getBytes(StandardCharsets.UTF_8);

        StegoOperations.EmbedResult result = operations.embed(
                cover, message, "passphrase", true, "passphrase", "zhang-tang", "aes");
        assertEquals(message.length + AesCipher.OVERHEAD_BYTES, result.payloadBytes());

        byte[] recovered = operations.extract(result.image(), "passphrase", true, "passphrase", "zhang-tang", "AES");
        assertArrayEquals(message, recovered);
    }

    @Test
    void pngRoundTrip(@TempDir Path dir) throws Exception {
        Path coverPath = dir.resolve("cover.png");
        Path stegoPath = dir.resolve("stego.png");
        ImageIO.write(noise(40, 40, 1), "png", coverPath.toFile());
        byte[] message = "file".getBytes(StandardCharsets.UTF_8);

        BufferedImage cover = StegoOperations.readImage(coverPath);
        StegoOperations.EmbedResult result = operations.embed(cover, message, "k", false, null, null, null);
        StegoOperations.writeStego(result.image(), stegoPath);

        byte[] recovered = operations.extract(StegoOperations.readImage(stegoPath), "k", false, null, null, null);
        assertArrayEquals(message, recovered);
    }

    @Test
    void capacityMatchesWhatTheSchemeWillEmbed() throws Exception {
        assertEquals(-1, StegoOperations.maxPayloadBytes(12, 1));
        assertEquals(0, StegoOperations.maxPayloadBytes(13, 1));
        assertFalse(StegoOperations.fits(12, 1, 0, false));
        assertTrue(StegoOperations.fits(13, 1, 0, false));
        assertFalse(StegoOperations.fits(13, 1, 1, false));

        int width = 24;
        int height = 24;
        int max = StegoOperations.maxPayloadBytes(width, height);
        assertTrue(max > 0);
        BufferedImage image = noise(width, height, 4);
        operations.embed(image, new byte[max], "k", false, null, "zhang-tang", "aes");
        assertThrows(MessageTooLongException.class,
                () -> operations.embed(image, new byte[max + 1], "k", false, null, "zhang-tang", "aes"));
        assertThrows(MessageTooLongException.class,
                () -> operations.embed(noise(8, 1, 1), new byte[0], "k", false, null, "zhang-tang", "aes"));
    }

    @Test
    void encryptedPayloadRespectsHeaderLimit() {
        int roomy = 420;
        int plaintext = AbstractScheme.MAX_PAYLOAD_LENGTH - AesCipher.OVERHEAD_BYTES;
        assertTrue(StegoOperations.fits(roomy, roomy, plaintext, true));
        assertFalse(StegoOperations.fits(roomy, roomy, plaintext + 1, true));
        assertEquals(plaintext + 1 + AesCipher.OVERHEAD_BYTES,
                StegoOperations.packagedLength(plaintext + 1, true));
    }

    @Test
    void rejectsUnknownSchemeAndCipherAndMissingKeys() throws Exception {
        BufferedImage image = noise(16, 16, 2);
        byte[] message = new byte[] {1};
        assertThrows(IllegalArgumentException.class,
                () -> operations.embed(image, message, "k", false, null, "nope", "aes"));
        assertThrows(IllegalArgumentException.class,
                () -> operations.embed(image, message, "k", true, "k", "zhang-tang", "rot13"));
        assertThrows(IllegalArgumentException.class,
                () -> operations.embed(image, message, null, false, null, "zhang-tang", "aes"));
        assertThrows(IllegalArgumentException.class,
                () -> operations.embed(image, message, "k", true, null, "zhang-tang", "aes"));
    }

    @Test
    void blankStegoKeyRoundTrips() throws Exception {
        BufferedImage cover = noise(40, 40, 11);
        byte[] message = "public-walk".getBytes(StandardCharsets.UTF_8);
        StegoOperations.EmbedResult stego = operations.embed(cover, message, "", false, null, "zhang-tang", "aes");
        assertArrayEquals(message, operations.extract(stego.image(), "", false, null, "zhang-tang", "aes"));
        assertThrows(CorruptOrInvalidStegoImageException.class,
                () -> operations.extract(stego.image(), "other", false, null, "zhang-tang", "aes"));
    }

    @Test
    void distinctStegoAndEncryptKeysRoundTrip() throws Exception {
        BufferedImage cover = noise(64, 64, 12);
        byte[] message = "split-keys".getBytes(StandardCharsets.UTF_8);
        StegoOperations.EmbedResult stego = operations.embed(
                cover, message, "walk", true, "cipher", "zhang-tang", "aes");

        assertArrayEquals(message, operations.extract(stego.image(), "walk", true, "cipher", "zhang-tang", "aes"));
        assertThrows(EncryptionException.class,
                () -> operations.extract(stego.image(), "walk", true, "walk", "zhang-tang", "aes"));
        assertThrows(EncryptionException.class,
                () -> operations.extract(stego.image(), "walk", true, "nope", "zhang-tang", "aes"));
        assertThrows(CorruptOrInvalidStegoImageException.class,
                () -> operations.extract(stego.image(), "cipher", true, "cipher", "zhang-tang", "aes"));
        assertThrows(CorruptOrInvalidStegoImageException.class,
                () -> operations.extract(stego.image(), "other", true, "cipher", "zhang-tang", "aes"));
    }

    @Test
    void emptyEncryptKeyIsAValidAeadPath() throws Exception {
        BufferedImage cover = noise(64, 64, 13);
        byte[] message = "blank-cipher".getBytes(StandardCharsets.UTF_8);
        StegoOperations.EmbedResult stego = operations.embed(
                cover, message, "walk", true, "", "zhang-tang", "aes");
        assertArrayEquals(message, operations.extract(stego.image(), "walk", true, "", "zhang-tang", "aes"));
        assertThrows(EncryptionException.class,
                () -> operations.extract(stego.image(), "walk", true, "walk", "zhang-tang", "aes"));
    }

    @Test
    void whitespaceOnlyKeyStillRoundTrips() throws Exception {
        BufferedImage cover = noise(32, 32, 8);
        byte[] message = "kept".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StegoOperations.EmbedResult stego = operations.embed(cover, message, "  ", false, null, "zhang-tang", "aes");
        assertArrayEquals(message, operations.extract(stego.image(), "  ", false, null, "zhang-tang", "aes"));
    }

    @Test
    void messageFileIsUtf8(@TempDir Path dir) throws Exception {
        Path text = dir.resolve("message.txt");
        Files.writeString(text, "héllo");
        assertArrayEquals("héllo".getBytes(StandardCharsets.UTF_8), StegoOperations.readMessageFile(text));

        Path binary = dir.resolve("message.bin");
        Files.write(binary, new byte[] {(byte) 0xFF, 0x00});
        assertThrows(java.io.IOException.class, () -> StegoOperations.readMessageFile(binary));
    }

    @Test
    void analyzeReturnsBothChecks() {
        StegoOperations.Report report = operations.analyze(noise(32, 16, 5));
        assertTrue(report.chiSquare().samples() > 0);
        assertTrue(Double.isFinite(report.chiSquare().pValue()));
        assertEquals("n/a", AnalysisFormat.rate(Double.NaN));
    }

    private static BufferedImage noise(int width, int height, int seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        return image;
    }
}
