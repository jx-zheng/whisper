package com.jxzheng.whisper.analysis;

import java.awt.image.BufferedImage;

/**
 * Test helper: naive sequential LSB replacement (not Zhang–Tang). Used as a
 * positive control that classic detectors respond to ordinary LSB embedding.
 */
public final class NaiveLsbEmbedder {

    private NaiveLsbEmbedder() {
    }

    public static BufferedImage embed(BufferedImage cover, byte[] message) {
        BufferedImage stego = new BufferedImage(
                cover.getWidth(), cover.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < cover.getHeight(); y++) {
            for (int x = 0; x < cover.getWidth(); x++) {
                stego.setRGB(x, y, cover.getRGB(x, y));
            }
        }

        int bitIndex = 0;
        int totalBits = message.length * 8;
        outer:
        for (int y = 0; y < stego.getHeight(); y++) {
            for (int x = 0; x < stego.getWidth(); x++) {
                if (bitIndex >= totalBits) {
                    break outer;
                }
                int rgb = stego.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                if (bitIndex < totalBits) {
                    r = (r & 0xFE) | bitAt(message, bitIndex++);
                }
                if (bitIndex < totalBits) {
                    g = (g & 0xFE) | bitAt(message, bitIndex++);
                }
                if (bitIndex < totalBits) {
                    b = (b & 0xFE) | bitAt(message, bitIndex++);
                }
                stego.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }

        if (bitIndex < totalBits) {
            throw new IllegalArgumentException(
                    "Message too large for cover (" + bitIndex + "/" + totalBits + " bits placed)");
        }
        return stego;
    }

    private static int bitAt(byte[] message, int bitIndex) {
        int byteIndex = bitIndex / 8;
        int offset = 7 - (bitIndex % 8);
        return (message[byteIndex] >> offset) & 1;
    }
}
