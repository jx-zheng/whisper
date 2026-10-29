package com.jxzheng.whisper.gui;

import java.awt.image.BufferedImage;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import com.jxzheng.whisper.ops.AnalysisFormat;
import com.jxzheng.whisper.ops.StegoOperations;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

/**
 * Exercises Embed, Extract, and Analyze when {@code WHISPER_GUI_SMOKE} is an output directory.
 * Off in normal launches.
 */
final class SmokeRun {

    private SmokeRun() {
    }

    static void start(
            Scene scene,
            StatusBar status,
            Button themeButton,
            ToggleButton embedTab,
            ToggleButton extractTab,
            ToggleButton analyzeTab,
            EmbedPane embed,
            ExtractPane extract,
            AnalyzePane analyze) {
        String directory = System.getenv("WHISPER_GUI_SMOKE");
        if (directory == null || directory.isBlank()) {
            return;
        }
        Path smokeDir = Path.of(directory);
        Thread thread = new Thread(() -> {
            try {
                run(smokeDir, scene, status, themeButton, embedTab, extractTab, analyzeTab, embed, extract, analyze);
                System.exit(0);
            } catch (Exception ex) {
                try {
                    Files.createDirectories(smokeDir);
                    Files.writeString(smokeDir.resolve("error.txt"), trace(ex));
                } catch (Exception ignored) {
                    // The stack trace on stderr is the fallback.
                }
                ex.printStackTrace(System.err);
                System.exit(1);
            }
        }, "whisper-smoke");
        thread.setDaemon(false);
        thread.start();
    }

    private static void run(
            Path smokeDir,
            Scene scene,
            StatusBar status,
            Button themeButton,
            ToggleButton embedTab,
            ToggleButton extractTab,
            ToggleButton analyzeTab,
            EmbedPane embed,
            ExtractPane extract,
            AnalyzePane analyze) throws Exception {
        Files.createDirectories(smokeDir);
        Path cover = smokeDir.resolve("cover.png");
        Path stego = smokeDir.resolve("stego.png");
        Path note = smokeDir.resolve("note.txt");
        Path fileStego = smokeDir.resolve("file-stego.png");
        String message = "whisper gui round trip";
        String key = "test-passphrase";
        writeCover(cover);
        Files.writeString(note, "from-file");

        fx(() -> {
            if ("Dark".equals(themeButton.getText())) {
                themeButton.fire();
            }
            embedTab.setSelected(true);
            embed.fileModeButton().setSelected(true);
            embed.coverField().setText(cover.toString());
            embed.messageFileField().setText(note.toString());
            embed.passphraseField().setText(key);
            embed.passphraseField().setRevealed(true);
            embed.encryptBox().setSelected(false);
            embed.outputField().setText(fileStego.toString());
        });
        fx(() -> {
            if (!embed.passphraseField().revealed()) {
                throw new IllegalStateException("Show did not reveal the passphrase");
            }
            if (!key.equals(embed.passphraseField().getText())) {
                throw new IllegalStateException("Revealed passphrase changed the value");
            }
            embed.passphraseField().setRevealed(false);
        });
        fx(() -> embed.embedButton().fire());
        waitIdle(status);
        assertOk(status);
        byte[] fromFile = new StegoOperations().extract(
                javax.imageio.ImageIO.read(fileStego.toFile()), key, false, "zhang-tang", "aes");
        if (!"from-file".equals(new String(fromFile, java.nio.charset.StandardCharsets.UTF_8))) {
            throw new IllegalStateException("File-mode embed did not round-trip");
        }

        fx(() -> {
            embed.textModeButton().setSelected(true);
            embed.messageArea().setText(message);
            embed.encryptBox().setSelected(true);
            embed.outputField().setText(stego.toString());
        });
        fx(() -> embed.embedButton().fire());
        waitIdle(status);
        assertOk(status);
        if (!Files.isRegularFile(stego)) {
            throw new IllegalStateException("Stego file was not written: " + stego);
        }
        screenshot(scene, smokeDir.resolve("embed-light.png"));

        fx(() -> embed.openExtractButton().fire());
        waitUntil("stego preview", extract::imageReady);
        fx(() -> {
            if (!extract.decryptBox().isSelected()) {
                throw new IllegalStateException("Decrypt was not copied to Extract");
            }
            if (!key.equals(extract.passphraseField().getText())) {
                throw new IllegalStateException("Passphrase was not copied to Extract");
            }
            extractTab.setSelected(true);
        });
        fx(() -> extract.extractButton().fire());
        waitIdle(status);
        assertOk(status);
        String recovered = fxGet(extract::resultText);
        if (!message.equals(recovered)) {
            throw new IllegalStateException("Extracted text was [" + recovered + "]");
        }
        screenshot(scene, smokeDir.resolve("extract-light.png"));

        fx(() -> {
            themeButton.fire();
            analyzeTab.setSelected(true);
            analyze.imageField().setText(stego.toString());
        });
        fx(() -> analyze.analyzeButton().fire());
        waitIdle(status);
        assertOk(status);
        StegoOperations.Report report = new StegoOperations().analyze(ImageIO.read(stego.toFile()));
        String expectedP = AnalysisFormat.pValue(report.chiSquare().pValue());
        String actualP = fxGet(analyze::pValueText);
        if (!expectedP.equals(actualP)) {
            throw new IllegalStateException("Chi-square p-value " + actualP + " != " + expectedP);
        }
        String expectedRate = AnalysisFormat.rate(report.rs().estimatedRate());
        String actualRate = fxGet(analyze::rateText);
        if (!expectedRate.equals(actualRate)) {
            throw new IllegalStateException("RS rate " + actualRate + " != " + expectedRate);
        }
        screenshot(scene, smokeDir.resolve("analyze-dark.png"));
        Files.writeString(smokeDir.resolve("ok.txt"), "ok\n");
    }

    private static void writeCover(Path cover) throws Exception {
        BufferedImage image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        if (!ImageIO.write(image, "png", cover.toFile())) {
            throw new IllegalStateException("Could not write cover");
        }
    }

    private static void waitIdle(StatusBar status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            if (!fxGet(status::busy)) {
                return;
            }
            Thread.sleep(40);
        }
        throw new IllegalStateException("Timed out. Status: " + fxGet(status::message));
    }

    private static void waitUntil(String what, Supplier<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (fxGet(condition::get)) {
                return;
            }
            Thread.sleep(40);
        }
        throw new IllegalStateException("Timed out waiting for " + what);
    }

    private static void assertOk(StatusBar status) throws Exception {
        StatusBar.Level level = fxGet(status::level);
        if (level == StatusBar.Level.ERROR) {
            throw new IllegalStateException(fxGet(status::message));
        }
    }

    private static void screenshot(Scene scene, Path file) throws Exception {
        fx(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            WritableImage image = scene.snapshot(null);
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            int[] pixels = new int[width * height];
            PixelReader reader = image.getPixelReader();
            reader.getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
            BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            buffered.setRGB(0, 0, width, height, pixels, 0, width);
            try {
                ImageIO.write(buffered, "png", file.toFile());
            } catch (Exception ex) {
                throw new IllegalStateException("Could not write " + file, ex);
            }
        });
    }

    private static void fx(Runnable action) throws Exception {
        fxGet(() -> {
            action.run();
            return Boolean.TRUE;
        });
    }

    private static <T> T fxGet(Supplier<T> supplier) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return supplier.get();
        }
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                value.set(supplier.get());
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("JavaFX thread stalled");
        }
        if (error.get() != null) {
            if (error.get() instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(error.get());
        }
        return value.get();
    }

    private static String trace(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
