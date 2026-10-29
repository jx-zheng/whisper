package com.jxzheng.whisper.ops;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import com.jxzheng.whisper.analysis.ChiSquareAnalyzer;
import com.jxzheng.whisper.analysis.RsAnalyzer;
import com.jxzheng.whisper.crypto.CipherRegistry;
import com.jxzheng.whisper.schemes.SchemeRegistry;

/**
 * Scheme lists, image loading, and steganalysis shared by the CLI and the desktop app.
 * Whisper 0.9 embed and extract live in the tests.
 */
public final class StegoOperations {

    public List<String> schemes() {
        return SchemeRegistry.shared().ids();
    }

    public List<String> ciphers() {
        return CipherRegistry.shared().ids();
    }

    public record Report(ChiSquareAnalyzer.Result chiSquare, RsAnalyzer.Result rs) {
    }

    public Report analyze(BufferedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        return new Report(new ChiSquareAnalyzer().analyze(image), new RsAnalyzer().analyze(image));
    }

    public static BufferedImage readImage(Path path) throws IOException {
        if (path == null || !Files.exists(path) || Files.isDirectory(path)) {
            throw new IOException("Could not read image: " + path);
        }
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new IOException("Could not read image: " + path);
        }
        return image;
    }
}
