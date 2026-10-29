package com.jxzheng.whisper.gui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;

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
}
