package com.jxzheng.whisper.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ImageFormatsTest {

    @Test
    void prefersPngWhenExtensionMissing() {
        assertEquals("png", ImageFormats.formatOf(Path.of("stego")));
    }

    @Test
    void normalizesJpegAliases() {
        assertEquals("jpg", ImageFormats.formatOf(Path.of("photo.jpeg")));
        assertTrue(ImageFormats.isLossy("jpeg"));
        assertTrue(ImageFormats.isLossy("jpg"));
    }

    @Test
    void treatsPngAsLossless() {
        assertTrue(ImageFormats.isLossless("png"));
        assertFalse(ImageFormats.isLossy("png"));
    }

    @Test
    void rejectsLossyEmbedOutput() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ImageFormats.requireLosslessOutput(Path.of("out.jpg")));
        assertTrue(ex.getMessage().contains("PNG"));
    }

    @Test
    void allowsPngEmbedOutput() {
        ImageFormats.requireLosslessOutput(Path.of("out.png"));
    }

    @Test
    void warnsOnLossyCover() {
        String warning = ImageFormats.lossyInputWarning(Path.of("cover.jpeg"));
        assertTrue(warning != null && warning.contains("lossy"));
    }

    @Test
    void noWarningOnPngCover() {
        assertNull(ImageFormats.lossyInputWarning(Path.of("cover.png")));
    }

    @Test
    void replaceExtensionSwapsSuffix() {
        assertEquals(Path.of("stego.png"), ImageFormats.replaceExtension(Path.of("stego.jpg"), "png"));
    }
}
