package com.jxzheng.whisper.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            assertIcon(icon, 512);
            assertTrue(hasTeal(icon), "icon is missing the teal mark");
        }
        for (int size : new int[] {16, 32, 48, 64, 128, 256}) {
            try (InputStream in = GuiAssetsTest.class.getResourceAsStream(
                    "/com/jxzheng/whisper/gui/icon-" + size + ".png")) {
                assertNotNull(in, "icon-" + size + ".png");
                BufferedImage icon = ImageIO.read(in);
                assertIcon(icon, size);
            }
        }
        try (InputStream in = GuiAssetsTest.class.getResourceAsStream(
                "/com/jxzheng/whisper/gui/icon-header.png")) {
            assertNotNull(in, "icon-header.png");
            BufferedImage header = ImageIO.read(in);
            assertIcon(header, 512);
            assertTrue(hasTeal(header), "header mark is missing the teal waves");
            assertTrue(hasLightFrame(header), "header mark needs a light frame on the dark chrome");
        }
    }

    /** The header frame is a light slate stroke, not the charcoal tile. */
    private static boolean hasLightFrame(BufferedImage icon) {
        int light = 0;
        for (int y = 0; y < icon.getHeight(); y += 2) {
            for (int x = 0; x < icon.getWidth(); x += 2) {
                int argb = icon.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                int r = (argb >> 16) & 0xff;
                int g = (argb >> 8) & 0xff;
                int b = argb & 0xff;
                if (a > 180 && r > 140 && g > 140 && b > 150 && Math.abs(r - g) < 30) {
                    light++;
                }
            }
        }
        return light > 40;
    }

    private static void assertIcon(BufferedImage icon, int size) {
        assertNotNull(icon);
        assertEquals(size, icon.getWidth());
        assertEquals(size, icon.getHeight());
        assertEquals(0, icon.getRGB(0, 0) >>> 24, "corner should stay transparent");
        assertEquals(0, icon.getRGB(size - 1, size - 1) >>> 24, "corner should stay transparent");
    }

    /** The waves are teal: green ahead of red, with blue still present. */
    private static boolean hasTeal(BufferedImage icon) {
        for (int y = 0; y < icon.getHeight(); y += 3) {
            for (int x = 0; x < icon.getWidth(); x += 3) {
                int argb = icon.getRGB(x, y);
                int r = (argb >> 16) & 0xff;
                int g = (argb >> 8) & 0xff;
                int b = argb & 0xff;
                if (g > 140 && g > r + 20 && b > 80) {
                    return true;
                }
            }
        }
        return false;
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
