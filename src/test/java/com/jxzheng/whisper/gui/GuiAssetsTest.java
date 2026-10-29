package com.jxzheng.whisper.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class GuiAssetsTest {

    @Test
    void stylesheetAndIconArePackaged() throws Exception {
        assertNotNull(GuiAssetsTest.class.getResource("/com/jxzheng/whisper/gui/whisper.css"));
        try (InputStream in = GuiAssetsTest.class.getResourceAsStream("/com/jxzheng/whisper/gui/icon.png")) {
            assertNotNull(in);
            BufferedImage icon = ImageIO.read(in);
            assertNotNull(icon);
            assertTrue(icon.getWidth() >= 64);
            assertTrue(icon.getHeight() >= 64);
        }
    }

    @Test
    void stylesheetIsDarkOnly() throws Exception {
        try (InputStream in = GuiAssetsTest.class.getResourceAsStream("/com/jxzheng/whisper/gui/whisper.css")) {
            assertNotNull(in);
            String css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(css.contains("-whisper-bg: #101218"));
            assertFalse(css.contains("theme-light"));
            assertFalse(css.contains("theme-dark"));
            assertFalse(css.contains("#ffffff"));
            assertFalse(css.contains("#fff"));
        }
    }
}
