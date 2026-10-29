package com.jxzheng.whisper.drivers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliDriverTest {

    @Test
    void embedExtractAnalyzeRoundTrip(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path stego = dir.resolve("stego.png");
        Path messageFile = dir.resolve("message.txt");
        ImageIO.write(noise(64, 64), "png", cover.toFile());
        Files.writeString(messageFile, "from-file");

        CliDriver driver = new CliDriver();
        try (Capture ignored = Capture.of()) {
            assertEquals(0, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", stego.toString(),
                    "-f", messageFile.toString(), "-k", "cli-key", "-s", "zt"
            }));
        }

        try (Capture out = Capture.of()) {
            int extracted = driver.run(new String[] {"extract", "-i", stego.toString(), "-k", "cli-key"});
            assertEquals(0, extracted);
            assertTrue(out.stdout().startsWith("from-file"));
        }

        try (Capture analysis = Capture.of()) {
            assertEquals(0, driver.run(new String[] {"analyze", "-i", stego.toString()}));
            String report = analysis.stdout();
            assertTrue(report.contains("Chi-square:"));
            assertTrue(report.contains("RS analysis:"));
            assertFalse(report.contains("<<<<<<<"));
        }
    }

    @Test
    void encryptedRoundTrip(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path stego = dir.resolve("stego.png");
        Path outFile = dir.resolve("out.txt");
        ImageIO.write(noise(64, 64), "png", cover.toFile());

        CliDriver driver = new CliDriver();
        try (Capture ignored = Capture.of()) {
            assertEquals(0, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", stego.toString(),
                    "-m", "hidden", "-k", "secret", "-e", "-c", "aes"
            }));
            assertEquals(0, driver.run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "secret", "-e", "-o", outFile.toString()
            }));
        }
        assertEquals("hidden", Files.readString(outFile));
    }

    @Test
    void rejectsGifOutput(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path stego = dir.resolve("stego.gif");
        ImageIO.write(noise(16, 16), "png", cover.toFile());

        try (Capture error = Capture.of()) {
            int code = new CliDriver().run(new String[] {
                    "embed", "-i", cover.toString(), "-o", stego.toString(), "-m", "x", "-k", "k"
            });
            assertEquals(1, code);
            assertTrue(error.stderr().contains("PNG or BMP"));
        }
    }

    @Test
    void warnsOnJpegCover(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.jpg");
        Path stego = dir.resolve("stego.png");
        ImageIO.write(noise(32, 32), "jpg", cover.toFile());

        try (Capture error = Capture.of()) {
            int code = new CliDriver().run(new String[] {
                    "embed", "-i", cover.toString(), "-o", stego.toString(), "-m", "x", "-k", "k"
            });
            assertEquals(0, code);
            assertTrue(error.stderr().contains("Warning:"));
        }
    }

    @Test
    void helpMentionsAnalyzeWithoutConflictMarkers() {
        try (Capture out = Capture.of()) {
            assertEquals(0, new CliDriver().run(new String[] {"help"}));
            String text = out.stdout();
            assertTrue(text.contains("analyze"));
            assertTrue(text.contains("PNG or BMP"));
            assertFalse(text.contains("gui"));
            assertFalse(text.contains("<<<<<<<"));
            assertFalse(text.contains(">>>>>>>"));
        }
    }

    @Test
    void guiIsNotACommand() {
        try (Capture out = Capture.of()) {
            assertEquals(1, new CliDriver().run(new String[] {"gui"}));
            assertTrue(out.stderr().contains("Unknown command"));
        }
    }

    private static BufferedImage noise(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(width * 31L + height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        return image;
    }

    private static final class Capture implements AutoCloseable {
        private final PrintStream out = System.out;
        private final PrintStream err = System.err;
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        static Capture of() {
            Capture capture = new Capture();
            System.setOut(new PrintStream(capture.stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(capture.stderr, true, StandardCharsets.UTF_8));
            return capture;
        }

        String stdout() {
            return stdout.toString(StandardCharsets.UTF_8);
        }

        String stderr() {
            return stderr.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            System.setOut(out);
            System.setErr(err);
        }
    }
}
