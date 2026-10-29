package com.jxzheng.whisper.drivers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        var embedded = com.jxzheng.whisper.ops.LegacyEmbed.embed(
                com.jxzheng.whisper.ops.StegoOperations.readImage(cover),
                "empty-aes".getBytes(StandardCharsets.UTF_8),
                "walk", true, "");
        ImageIO.write(embedded.image(), "png", stego.toFile());
        Path out = dir.resolve("out.txt");
        try (Capture note = Capture.of()) {
            assertEquals(0, new CliDriver().run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "walk", "-e", "-o", out.toString()
            }));
            assertTrue(note.stderr().contains("AES-GCM tag OK"), note.stderr());
        }
        assertEquals("empty-aes", Files.readString(out));
        Path aesOut = dir.resolve("aes-flag.txt");
        assertEquals(0, new CliDriver().run(new String[] {
                "extract", "-i", stego.toString(), "-k", "walk", "-c", "aes", "-o", aesOut.toString()
        }));
        assertEquals("empty-aes", Files.readString(aesOut));
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
    void hillOnJpegCoverIsRefused(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.jpg");
        ImageIO.write(noise(48, 48), "jpg", cover.toFile());
        CliDriver driver = new CliDriver();
        for (String scheme : new String[] {"hill-stc", "hill"}) {
            for (String ext : new String[] {"png", "jpg"}) {
                Path out = dir.resolve("out-" + scheme + "." + ext);
                try (Capture error = Capture.of()) {
                    assertEquals(1, driver.run(new String[] {
                            "embed", "-i", cover.toString(), "-o", out.toString(),
                            "-m", "x", "-k", "pw", "-s", scheme, "--kdf", "test"
                    }), error.stderr());
                    assertTrue(error.stderr().contains("HILL needs a PNG or BMP cover"), error.stderr());
                    assertTrue(error.stderr().contains("uerd-stc"), error.stderr());
                    assertFalse(error.stderr().contains("Refusing to write"), error.stderr());
                    assertFalse(Files.exists(out));
                }
            }
        }
    }

    @Test
    void capacityEstimateDoesNotExceedWhatFits(@TempDir Path dir) throws Exception {
        CliDriver driver = new CliDriver();
        Path tiny = dir.resolve("tiny.png");
        Path tinyOut = dir.resolve("tiny-out.png");
        ImageIO.write(noise(96, 96), "png", tiny.toFile());
        try (Capture error = Capture.of()) {
            assertEquals(1, driver.run(new String[] {
                    "embed", "-i", tiny.toString(), "-o", tinyOut.toString(),
                    "-m", "x", "-k", "pw", "--kdf", "test"
            }));
            assertEquals(0, aboutContent(error.stderr()), error.stderr());
            assertFalse(Files.exists(tinyOut));
        }

        Path cover = dir.resolve("cover.png");
        Path out = dir.resolve("stego.png");
        ImageIO.write(noise(160, 120), "png", cover.toFile());
        Path textFile = dir.resolve("big.txt");
        Files.write(textFile, incompressibleText(20_000));
        int textRoom;
        try (Capture error = Capture.of()) {
            assertEquals(1, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", out.toString(),
                    "-f", textFile.toString(), "-k", "pw", "--kdf", "test"
            }));
            textRoom = aboutContent(error.stderr());
            assertTrue(textRoom > 0 && textRoom < 20_000, error.stderr());
        }
        Files.write(textFile, incompressibleText(textRoom));
        try (Capture ok = Capture.of()) {
            assertEquals(0, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", out.toString(),
                    "-f", textFile.toString(), "-k", "pw", "--kdf", "test"
            }), ok.stderr());
        }

        Path named = dir.resolve("notes.bin");
        Files.write(named, incompressible(20_000));
        int fileRoom;
        try (Capture error = Capture.of()) {
            assertEquals(1, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", out.toString(),
                    "--file", named.toString(), "-k", "pw", "--kdf", "test"
            }));
            fileRoom = aboutContent(error.stderr());
            assertTrue(fileRoom > 0 && fileRoom < textRoom, error.stderr());
        }
        Files.write(named, incompressible(fileRoom));
        Path fileOut = dir.resolve("file-stego.png");
        try (Capture ok = Capture.of()) {
            assertEquals(0, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", fileOut.toString(),
                    "--file", named.toString(), "-k", "pw", "--kdf", "test"
            }), ok.stderr());
        }
        Files.write(named, incompressible(fileRoom + 1));
        try (Capture error = Capture.of()) {
            assertEquals(1, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", dir.resolve("over.png").toString(),
                    "--file", named.toString(), "-k", "pw", "--kdf", "test"
            }));
            assertTrue(aboutContent(error.stderr()) <= fileRoom, error.stderr());
        }
    }

    @Test
    void conflictingPayloadSourcesAreRejected(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path out = dir.resolve("out.png");
        Path msg = dir.resolve("msg.txt");
        Path file = dir.resolve("payload.bin");
        Path image = dir.resolve("secret.png");
        ImageIO.write(noise(32, 32), "png", cover.toFile());
        ImageIO.write(noise(8, 8), "png", image.toFile());
        Files.writeString(msg, "from-file");
        Files.write(file, new byte[] {1, 2, 3});
        CliDriver driver = new CliDriver();
        String[][] commands = {
                {"embed", "-i", cover.toString(), "-o", out.toString(), "-k", "pw", "-m", "from-m", "-f", msg.toString()},
                {"embed", "-i", cover.toString(), "-o", out.toString(), "-k", "pw", "-m", "from-m", "--image", image.toString()},
                {"embed", "-i", cover.toString(), "-o", out.toString(), "-k", "pw", "--file", file.toString(), "--image", image.toString()}
        };
        for (String[] command : commands) {
            try (Capture error = Capture.of()) {
                assertEquals(1, driver.run(command), error.stderr());
                assertTrue(error.stderr().contains("only one"), error.stderr());
                assertFalse(Files.exists(out));
            }
        }
    }

    @Test
    void fitMaxEdgeRejectsNonPositiveAndNonNumeric(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path out = dir.resolve("out.png");
        Path image = dir.resolve("secret.png");
        ImageIO.write(noise(32, 32), "png", cover.toFile());
        ImageIO.write(noise(8, 8), "png", image.toFile());
        CliDriver driver = new CliDriver();
        for (String edge : new String[] {"-5", "0"}) {
            try (Capture error = Capture.of()) {
                assertEquals(1, driver.run(new String[] {
                        "embed", "-i", cover.toString(), "-o", out.toString(), "-k", "pw",
                        "--image", image.toString(), "--fit-max-edge", edge, "--kdf", "test"
                }), error.stderr());
                assertTrue(error.stderr().contains("positive"), error.stderr());
                assertFalse(Files.exists(out));
            }
        }
        try (Capture error = Capture.of()) {
            assertEquals(1, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", out.toString(), "-k", "pw",
                    "--image", image.toString(), "--fit-max-edge", "abc"
            }));
            assertTrue(error.stderr().contains("--fit-max-edge"), error.stderr());
            assertTrue(error.stderr().contains("whole number"), error.stderr());
            assertFalse(error.stderr().contains("For input string"), error.stderr());
        }
        try (Capture error = Capture.of()) {
            assertEquals(1, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", out.toString(), "-k", "pw",
                    "--image", image.toString(), "--fit-quality", "abc"
            }));
            assertTrue(error.stderr().contains("--fit-quality"), error.stderr());
            assertFalse(error.stderr().contains("For input string"), error.stderr());
        }
    }

    @Test
    void extractOutDirCreatesMissingDirectory(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path stego = dir.resolve("stego.png");
        Path payload = dir.resolve("payload.bin");
        Files.write(payload, new byte[] {9, 8, 7, 6});
        ImageIO.write(noise(160, 120), "png", cover.toFile());
        CliDriver driver = new CliDriver();
        try (Capture embed = Capture.of()) {
            assertEquals(0, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", stego.toString(),
                    "--file", payload.toString(), "-k", "pw", "--kdf", "test"
            }), embed.stderr());
        }
        Path missing = dir.resolve("created").resolve("nested");
        try (Capture out = Capture.of()) {
            assertEquals(0, driver.run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "pw", "--kdf", "test",
                    "--out-dir", missing.toString()
            }), out.stderr());
            assertTrue(out.stderr().contains("Verified"), out.stderr());
            assertFalse(out.stderr().contains("Error:"), out.stderr());
        }
        assertArrayEquals(new byte[] {9, 8, 7, 6}, Files.readAllBytes(missing.resolve("payload.bin")));

        Path blocker = dir.resolve("not-a-dir");
        Files.writeString(blocker, "x");
        try (Capture error = Capture.of()) {
            assertEquals(1, driver.run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "pw", "--kdf", "test",
                    "--out-dir", blocker.toString()
            }));
            assertTrue(error.stderr().contains("Output directory does not exist"), error.stderr());
            assertFalse(error.stderr().contains("Verified"), error.stderr());
        }
    }

    @Test
    void extractOutputMissingParentNamesTheDirectory(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path stego = dir.resolve("stego.png");
        ImageIO.write(noise(160, 120), "png", cover.toFile());
        CliDriver driver = new CliDriver();
        try (Capture embed = Capture.of()) {
            assertEquals(0, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", stego.toString(),
                    "-m", "hello", "-k", "pw", "--kdf", "test"
            }), embed.stderr());
        }
        Path dest = dir.resolve("missing").resolve("msg.txt");
        try (Capture error = Capture.of()) {
            assertEquals(1, driver.run(new String[] {
                    "extract", "-i", stego.toString(), "-k", "pw", "--kdf", "test",
                    "-o", dest.toString()
            }));
            assertTrue(error.stderr().contains("Output directory does not exist"), error.stderr());
            assertTrue(error.stderr().contains(dir.resolve("missing").toString()), error.stderr());
            assertFalse(error.stderr().contains("File not found"), error.stderr());
            assertFalse(Files.exists(dest));
        }
    }

    @Test
    void extractNonImageSaysCouldNotRead(@TempDir Path dir) throws Exception {
        Path bin = dir.resolve("payload.bin");
        Files.write(bin, new byte[] {1, 2, 3, 4, 5});
        try (Capture error = Capture.of()) {
            assertEquals(1, new CliDriver().run(new String[] {
                    "extract", "-i", bin.toString(), "-k", "pw"
            }));
            assertTrue(error.stderr().contains("Could not read image"), error.stderr());
            assertTrue(error.stderr().contains("payload.bin"), error.stderr());
            assertFalse(error.stderr().contains("Nothing found"), error.stderr());
        }
    }

    @Test
    void missingFilesNameThePath(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        Path out = dir.resolve("out.png");
        ImageIO.write(noise(16, 16), "png", cover.toFile());
        CliDriver driver = new CliDriver();
        try (Capture error = Capture.of()) {
            assertEquals(1, driver.run(new String[] {
                    "embed", "-i", dir.resolve("nope.png").toString(), "-o", out.toString(), "-m", "x"
            }));
            assertTrue(error.stderr().contains("File not found"), error.stderr());
            assertTrue(error.stderr().contains("nope.png"), error.stderr());
        }
        try (Capture error = Capture.of()) {
            assertEquals(1, driver.run(new String[] {
                    "embed", "-i", cover.toString(), "-o", out.toString(),
                    "-f", dir.resolve("nope.txt").toString(), "-k", "k"
            }));
            assertTrue(error.stderr().contains("File not found"), error.stderr());
            assertTrue(error.stderr().contains("nope.txt"), error.stderr());
        }
    }

    @Test
    void analyzeAndHelpTreatZhangTangAsExtractOnly(@TempDir Path dir) throws Exception {
        Path cover = dir.resolve("cover.png");
        ImageIO.write(noise(32, 32), "png", cover.toFile());
        try (Capture analysis = Capture.of()) {
            assertEquals(0, new CliDriver().run(new String[] {"analyze", "-i", cover.toString()}));
            assertTrue(analysis.stdout().contains("extract-only"), analysis.stdout());
            assertFalse(analysis.stdout().contains("typically moves"), analysis.stdout());
        }
        try (Capture help = Capture.of()) {
            assertEquals(0, new CliDriver().run(new String[] {"--help"}));
            String text = help.stdout();
            assertTrue(text.contains("extract-only"), text);
            assertTrue(text.contains("EXIF") || text.toLowerCase(Locale.ROOT).contains("location"), text);
            assertFalse(text.contains("Keep safe metadata"), text);
            assertFalse(text.contains("typically moves"), text);
        }
    }

    @Test
    void guiIsNotACommand() {
        try (Capture out = Capture.of()) {
            assertEquals(1, new CliDriver().run(new String[] {"gui"}));
            assertTrue(out.stderr().contains("Unknown command"));
        }
    }

    private static int aboutContent(String stderr) {
        Matcher matcher = Pattern.compile("about (\\d+) bytes of content").matcher(stderr);
        assertTrue(matcher.find(), stderr);
        return Integer.parseInt(matcher.group(1));
    }

    private static byte[] incompressible(int n) {
        byte[] data = new byte[n];
        new Random(n * 17L + 3).nextBytes(data);
        for (int i = 0; i < data.length && i < 8; i++) {
            data[i] = (byte) (i + 1);
        }
        return data;
    }

    /** Printable ASCII. Valid for {@code -f}, and dense enough that DEFLATE does not shrink it. */
    private static byte[] incompressibleText(int n) {
        byte[] data = new byte[n];
        Random random = new Random(n * 31L + 9);
        for (int i = 0; i < n; i++) {
            data[i] = (byte) (33 + random.nextInt(94));
        }
        return data;
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
