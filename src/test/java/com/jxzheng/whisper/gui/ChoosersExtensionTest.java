package com.jxzheng.whisper.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChoosersExtensionTest {

    @Test
    void jpegSaveFilterMapsToJpg() {
        assertEquals("jpg", Choosers.saveExtension("JPEG image"));
        assertEquals("jpg", Choosers.saveExtension("jpg"));
        assertEquals("png", Choosers.saveExtension("PNG image"));
        assertEquals("bmp", Choosers.saveExtension("BMP image"));
    }
}
