package com.jxzheng.whisper.drivers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModernCliTest {

    static {
        System.setProperty("whisper.kdf.test", "true");
    }

    @Test
    void textHillChaChaAndAesAndIntegrity(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(160, 120, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", cover.toFile());
        roundTrip(dir, cover, "hill-stc", "chacha20-poly1305", "png");
        roundTrip(dir, cover, "hill-stc", "aes-256-gcm", "png");
    }

    @Test
    void jpegUerdRoundTrip(@TempDir Path dir) throws Exception {
        Path cover = Path.of(ModernCliTest.class.getResource("/fixtures/jpeg/photo-qf75.jpg").toURI());
        Path out = dir.resolve("stego.jpg");
        Path saved = dir.resolve("out.txt");
        CliDriver driver = new CliDriver();
        assertEquals(0, driver.run(new String[] {
                "embed", "-i", cover.toString(), "-o", out.toString(), "-m", "hi",
                "--scheme", "uerd-stc", "--cipher", "chacha20-poly1305", "--kdf", "test", "-k", "walk"
        }));
        assertEquals(0, driver.run(new String[] {
                "extract", "-i", out.toString(), "--scheme", "uerd-stc", "--kdf", "test", "-k", "walk",
                "-o", saved.toString()
        }));
        assertEquals("hi", Files.readString(saved));
    }

    @Test
    void optimizedHuffmanJpegEmbeds(@TempDir Path dir) throws Exception {
        Path cover = Path.of(ModernCliTest.class.getResource("/fixtures/jpeg/optimized-qf75.jpg").toURI());
        Path out = dir.resolve("stego.jpg");
        Path saved = dir.resolve("out.txt");
        CliDriver driver = new CliDriver();
        assertEquals(0, driver.run(new String[] {
                "embed", "-i", cover.toString(), "-o", out.toString(), "-m", "hi",
                "--scheme", "uerd-stc", "--kdf", "test", "-k", "walk"
        }));
        assertEquals(0, driver.run(new String[] {
                "extract", "-i", out.toString(), "--kdf", "test", "-k", "walk", "-o", saved.toString()
        }));
        assertEquals("hi", Files.readString(saved));
    }

    @Test
    void fileAndWrongKey(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(160, 120, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", cover.toFile());
        Path payload = dir.resolve("note.bin");
        Files.write(payload, new byte[] {1, 2, 3, 4, 9});
        Path out = dir.resolve("stego.png");
        Path outDir = dir.resolve("extracted");
        Files.createDirectories(outDir);
        CliDriver driver = new CliDriver();
        assertEquals(0, driver.run(new String[] {
                "embed", "-i", cover.toString(), "-o", out.toString(), "--file", payload.toString(),
                "--scheme", "hill-stc", "--kdf", "test", "-k", "walk", "-E", "cipher"
        }));
        assertEquals(0, driver.run(new String[] {
                "extract", "-i", out.toString(), "--scheme", "auto", "--kdf", "test",
                "-k", "walk", "-E", "cipher", "--out-dir", outDir.toString()
        }));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 9}, Files.readAllBytes(outDir.resolve("note.bin")));
        try (Capture capture = capture()) {
            assertEquals(1, driver.run(new String[] {
                    "extract", "-i", out.toString(), "--kdf", "test",
                    "-k", "walk", "-E", "cipher", "--out-dir", outDir.toString()
            }));
            assertTrue(capture.err().contains("Refusing to overwrite"), capture.err());
        }
        assertEquals(2, driver.run(new String[] {
                "extract", "-i", out.toString(), "--scheme", "hill-stc", "--kdf", "test", "-k", "walk", "-E", "nope"
        }));
    }

    @Test
    void legacyFixtureStillExtracts(@TempDir Path dir) throws Exception {
        Path stego = Path.of(ModernCliTest.class.getResource("/fixtures/legacy/zhang-tang-plain.png").toURI());
        Path out = dir.resolve("out.txt");
        assertEquals(0, new CliDriver().run(new String[] {
                "extract", "-i", stego.toString(), "-k", "legacy-stego-key", "-o", out.toString()
        }));
        assertEquals("legacy-plain-v0.9", Files.readString(out));
    }

    private static void roundTrip(Path dir, Path cover, String scheme, String cipher, String ext) throws Exception {
        Path out = dir.resolve(scheme + "-" + cipher + "." + ext);
        Path saved = dir.resolve(scheme + "-" + cipher + ".txt");
        CliDriver driver = new CliDriver();
        int code;
        try (Capture capture = capture()) {
            code = driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", out.toString(), "-m", "hello-" + cipher,
                    "--scheme", scheme, "--cipher", cipher, "--kdf", "test", "-k", "walk"
            });
            assertEquals(0, code, capture.err());
            assertTrue(capture.err().contains("SHA-256"), capture.err());
        }
        try (Capture capture = capture()) {
            code = driver.run(new String[] {
                    "extract", "-i", out.toString(), "--scheme", scheme, "--cipher", cipher,
                    "--kdf", "test", "-k", "walk", "-o", saved.toString()
            });
            assertEquals(0, code, capture.err());
            assertTrue(capture.err().contains("Verified"), capture.err());
            assertTrue(capture.err().contains("SHA-256"), capture.err());
        }
        assertEquals("hello-" + cipher, Files.readString(saved));
    }

    private static Capture capture() {
        return new Capture();
    }

    private static final class Capture implements AutoCloseable {
        private final java.io.PrintStream out = System.out;
        private final java.io.PrintStream err = System.err;
        private final java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        private final java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();

        Capture() {
            System.setOut(new java.io.PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(stderr, true, StandardCharsets.UTF_8));
        }

        String err() {
            return stderr.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            System.setOut(out);
            System.setErr(err);
        }
    }
}
