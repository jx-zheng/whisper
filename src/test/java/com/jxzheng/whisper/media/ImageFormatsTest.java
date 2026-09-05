package com.jxzheng.whisper.media;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void treatsPngAndBmpAsSafeStegoOutput() {
        assertTrue(ImageFormats.isSafeStegoOutput("png"));
        assertTrue(ImageFormats.isSafeStegoOutput("bmp"));
        assertFalse(ImageFormats.isSafeStegoOutput("gif"));
        assertFalse(ImageFormats.isLossy("png"));
    }

    @Test
    void rejectsLossyAndUnsafeEmbedOutputs() {
        for (String name : new String[] {"out.jpg", "out.jpeg", "out.webp", "out.gif", "out.xyz"}) {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> ImageFormats.requireLosslessOutput(Path.of(name)),
                    name);
            assertTrue(ex.getMessage().contains("PNG") || ex.getMessage().contains("BMP"), ex.getMessage());
        }
    }

    @Test
    void allowsPngAndBmpEmbedOutput() {
        assertDoesNotThrow(() -> ImageFormats.requireLosslessOutput(Path.of("out.png")));
        assertDoesNotThrow(() -> ImageFormats.requireLosslessOutput(Path.of("out.bmp")));
    }

    @Test
    void warnsOnLossyCover() {
        String warning = ImageFormats.lossyInputWarning(Path.of("cover.jpeg"));
        assertNotNull(warning);
        assertTrue(warning.contains("lossy"));
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
