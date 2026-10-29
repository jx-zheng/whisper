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

    static {
        System.setProperty("whisper.kdf.test", "true");
    }

    @Test
    void embedExtractAnalyzeRoundTrip(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path stego = dir.resolve("stego.png");
        Path messageFile = dir.resolve("message.txt");
        ImageIO.write(noise(200, 160), "png", cover.toFile());
        Files.writeString(messageFile, "from-file");

        CliDriver driver = new CliDriver();
        try (Capture embed = Capture.of()) {
            assertEquals(0, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", stego.toString(),
                    "-f", messageFile.toString(), "-k", "cli-key", "--kdf", "test"
            }));
            assertTrue(embed.stderr().contains("Verified"));
            assertTrue(embed.stderr().contains("SHA-256"));
        }

        try (Capture out = Capture.of()) {
            int extracted = driver.run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "cli-key", "--kdf", "test"
            });
            assertEquals(0, extracted);
            assertTrue(out.stdout().startsWith("from-file"));
            assertTrue(out.stderr().contains("Verified"));
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
    void whitespaceKeyRoundTrips(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path stego = dir.resolve("stego.png");
        ImageIO.write(noise(200, 160), "png", cover.toFile());

        CliDriver driver = new CliDriver();
        try (Capture ignored = Capture.of()) {
            assertEquals(0, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", stego.toString(),
                    "-m", "spaces", "-k", "  ", "--kdf", "test"
            }));
            assertEquals(0, driver.run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "  ", "--kdf", "test",
                    "-o", dir.resolve("out.txt").toString()
            }));
        }
        assertEquals("spaces", Files.readString(dir.resolve("out.txt")));
    }

    @Test
    void blankStegoKeyRoundTripsWhenOmitted(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path stego = dir.resolve("stego.png");
        Path outFile = dir.resolve("out.txt");
        ImageIO.write(noise(200, 160), "png", cover.toFile());

        CliDriver driver = new CliDriver();
        try (Capture note = Capture.of()) {
            assertEquals(0, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", stego.toString(), "-m", "no-key", "--kdf", "test"
            }));
            assertTrue(note.stderr().contains("not confidential"));
            assertEquals(0, driver.run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "", "--kdf", "test", "-o", outFile.toString()
            }));
            assertTrue(note.stderr().contains("not confidential"));
            assertFalse(note.stderr().contains("Verified ·"));
        }
        assertEquals("no-key", Files.readString(outFile));
    }

    @Test
    void distinctStegoAndEncryptKeysRoundTrip(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path stego = dir.resolve("stego.png");
        Path outFile = dir.resolve("out.txt");
        ImageIO.write(noise(200, 160), "png", cover.toFile());

        CliDriver driver = new CliDriver();
        try (Capture ignored = Capture.of()) {
            assertEquals(0, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", stego.toString(),
                    "-m", "hidden", "-k", "walk", "-E", "cipher", "-c", "aes-256-gcm", "--kdf", "test"
            }));
            assertEquals(0, driver.run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "walk", "-E", "cipher", "--kdf", "test",
                    "-o", outFile.toString()
            }));
        }
        assertEquals("hidden", Files.readString(outFile));

        try (Capture error = Capture.of()) {
            assertEquals(2, driver.run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "walk", "-E", "nope", "--kdf", "test"
            }));
            assertTrue(error.stderr().contains("Wrong encryption"));
        }
        try (Capture error = Capture.of()) {
            assertEquals(3, driver.run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "cipher", "-E", "cipher", "--kdf", "test"
            }));
            assertTrue(error.stderr().contains("Nothing found"));
        }
    }

    @Test
    void omittedEncryptKeyUsesStegoPassphrase(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path stego = dir.resolve("stego.png");
        Path outFile = dir.resolve("out.txt");
        ImageIO.write(noise(200, 160), "png", cover.toFile());

        CliDriver driver = new CliDriver();
        try (Capture note = Capture.of()) {
            assertEquals(0, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", stego.toString(),
                    "-m", "single-key", "-k", "walk", "-e", "--kdf", "test"
            }));
            assertFalse(note.stderr().contains("not confidential"));
            assertTrue(note.stderr().contains("Verified ·"));
        }
        try (Capture note = Capture.of()) {
            assertEquals(0, driver.run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "walk", "--kdf", "test", "-o", outFile.toString()
            }));
            assertTrue(note.stderr().contains("Verified ·"));
            assertFalse(note.stderr().contains("not confidential"));
        }
        assertEquals("single-key", Files.readString(outFile));

        try (Capture error = Capture.of()) {
            assertEquals(2, driver.run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "walk", "-E", "walk", "--kdf", "test"
            }));
            assertTrue(error.stderr().contains("Wrong encryption"));
        }
    }

    @Test
    void emptyEncryptKeyRoundTrips(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path stego = dir.resolve("stego.png");
        Path outFile = dir.resolve("out.txt");
        ImageIO.write(noise(200, 160), "png", cover.toFile());

        CliDriver driver = new CliDriver();
        try (Capture note = Capture.of()) {
            assertEquals(0, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", stego.toString(),
                    "-m", "open-cipher", "-k", "walk", "-E", "", "--kdf", "test"
            }));
            assertEquals(0, driver.run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "walk", "-E", "", "--kdf", "test",
                    "-o", outFile.toString()
            }));
            assertTrue(note.stderr().contains("not confidential"));
        }
        assertEquals("open-cipher", Files.readString(outFile));
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
            assertEquals(1, code);
            assertTrue(error.stderr().toLowerCase(java.util.Locale.ROOT).contains("jpg"));
        }
    }

    @Test
    void aes128EmbedIsRejected(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path stego = dir.resolve("stego.png");
        ImageIO.write(noise(32, 32), "png", cover.toFile());
        try (Capture error = Capture.of()) {
            assertEquals(1, new CliDriver().run(new String[] {
                    "embed", "-i", cover.toString(), "-o", stego.toString(),
                    "-m", "hi", "-k", "k", "-c", "aes", "--kdf", "test"
            }));
            assertTrue(error.stderr().contains("decrypt-only"));
        }
    }

    @Test
    void kdfTestIsNotAProductionChoice(@TempDir Path dir) throws Exception {
        String previous = System.getProperty("whisper.kdf.test");
        System.clearProperty("whisper.kdf.test");
        try {
            Path cover = dir.resolve("cover.png");
            Path stego = dir.resolve("stego.png");
            ImageIO.write(noise(32, 32), "png", cover.toFile());
            try (Capture error = Capture.of()) {
                assertEquals(1, new CliDriver().run(new String[] {
                        "embed", "-i", cover.toString(), "-o", stego.toString(),
                        "-m", "x", "-k", "k", "--kdf", "test"
                }));
                assertTrue(error.stderr().contains("not a production profile"));
            }
        } finally {
            if (previous == null) {
                System.clearProperty("whisper.kdf.test");
            } else {
                System.setProperty("whisper.kdf.test", previous);
            }
        }
    }

    @Test
    void legacyPlainExtractsWithoutModernFlags(@TempDir Path dir) throws Exception {
        Path stego = Path.of(CliDriverTest.class.getResource("/fixtures/legacy/zhang-tang-plain.png").toURI());
        Path out = dir.resolve("out.txt");
        try (Capture note = Capture.of()) {
            assertEquals(0, new CliDriver().run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "legacy-stego-key", "-o", out.toString()
            }));
            assertTrue(note.stderr().contains("Unverified (legacy format"));
        }
        assertEquals("legacy-plain-v0.9", Files.readString(out));
    }

    @Test
    void legacyAesShowsTagOkAndNeverDumpsCiphertext(@TempDir Path dir) throws Exception {
        Path stego = Path.of(CliDriverTest.class.getResource("/fixtures/legacy/zhang-tang-aes.png").toURI());
        Path out = dir.resolve("out.txt");
        try (Capture note = Capture.of()) {
            assertEquals(0, new CliDriver().run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "legacy-stego-key",
                    "-E", "legacy-enc-key", "-o", out.toString()
            }));
            assertTrue(note.stderr().contains("AES-GCM tag OK"));
            assertTrue(note.stdout().isEmpty());
        }
        assertEquals("legacy-encrypted-v0.9", Files.readString(out));

        try (Capture error = Capture.of()) {
            assertEquals(2, new CliDriver().run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "legacy-stego-key"
            }));
            assertTrue(error.stderr().contains("Ciphertext is not shown"), error.stderr());
            assertTrue(error.stdout().isEmpty());
            assertFalse(error.stderr().contains("legacy-encrypted-v0.9"));
        }
    }

    @Test
    void legacyAesFlagIsAcceptedOnExtract(@TempDir Path dir) throws Exception {
        Path stego = Path.of(CliDriverTest.class.getResource("/fixtures/legacy/zhang-tang-aes.png").toURI());
        Path out = dir.resolve("out.txt");
        assertEquals(0, new CliDriver().run(new String[] {
                "extract", "-i", stego.toString(), "-k", "legacy-stego-key",
                "-c", "aes", "-E", "legacy-enc-key", "-o", out.toString()
        }));
        assertEquals("legacy-encrypted-v0.9", Files.readString(out));
    }

    @Test
    void legacyEmptyKeyFollowsEncryptFlag(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path stego = dir.resolve("stego.png");
        ImageIO.write(noise(80, 80), "png", cover.toFile());
        var embedded = new com.jxzheng.whisper.ops.StegoOperations().embed(
                com.jxzheng.whisper.ops.StegoOperations.readImage(cover),
                "empty-aes".getBytes(StandardCharsets.UTF_8),
                "walk", true, "", "zhang-tang", "aes");
        ImageIO.write(embedded.image(), "png", stego.toFile());
        Path out = dir.resolve("out.txt");
        try (Capture note = Capture.of()) {
            assertEquals(0, new CliDriver().run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "walk", "-e", "-o", out.toString()
            }));
            assertTrue(note.stderr().contains("AES-GCM tag OK"), note.stderr());
        }
        assertEquals("empty-aes", Files.readString(out));
    }

    @Test
    void zhangTangEmbedIsRejected(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path stego = dir.resolve("stego.png");
        ImageIO.write(noise(32, 32), "png", cover.toFile());
        try (Capture error = Capture.of()) {
            assertEquals(1, new CliDriver().run(new String[] {
                    "embed", "-i", cover.toString(), "-o", stego.toString(),
                    "-m", "hi", "-k", "k", "-s", "zhang-tang", "--kdf", "test"
            }));
            assertTrue(error.stderr().contains("decrypt-only"));
        }
    }

    @Test
    void helpMentionsAnalyzeWithoutConflictMarkers() {
        try (Capture out = Capture.of()) {
            assertEquals(0, new CliDriver().run(new String[] {"help"}));
            String text = out.stdout();
            assertTrue(text.contains("analyze"));
            assertTrue(text.contains("PNG or BMP"));
            assertTrue(text.contains("encrypt-key"));
            assertFalse(text.contains("also used as cipher"));
            assertFalse(text.contains("inspect"));
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
