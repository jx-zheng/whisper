package com.jxzheng.whisper.schemes;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

/**
 * Base type for image steganography schemes.
 * Subclasses embed and extract arbitrary byte payloads in a cover image.
 */
public abstract class AbstractScheme {

    public static final int USABLE_BITS_PER_PIXEL = 3;
    public static final int BITS_PER_BYTE = 8;
    public static final byte START_OF_TRANSMISSION = 0x02;
    public static final int MAX_PAYLOAD_LENGTH = 65_535;
    public static final int MESSAGE_HEADER_LENGTH = 3;
    /**
     * One reference pixel plus enough pixels to carry the 3-byte header
     * (24 bits at 3 bits/pixel).
     */
    public static final int HEADER_POINTS =
            1 + (MESSAGE_HEADER_LENGTH * BITS_PER_BYTE) / USABLE_BITS_PER_PIXEL;
    public static final List<String> RGB_COLORS = List.of("RED", "GREEN", "BLUE");

    private final BufferedImage image;
    private final int imageWidth;
    private final int imageHeight;
    private final String key;
    private Random random;

    protected AbstractScheme(BufferedImage image, String key) {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must not be null or empty");
        }
        this.image = image;
        this.imageWidth = image.getWidth();
        this.imageHeight = image.getHeight();
        this.key = key;
        this.random = new Random(key.hashCode());
    }

    public BufferedImage getImage() {
        return image;
    }

    public int getImageWidth() {
        return imageWidth;
    }

    public int getImageHeight() {
        return imageHeight;
    }

    public Random getRandom() {
        return random;
    }

    public void restartRandomSequence() {
        this.random = new Random(key.hashCode());
    }

    public abstract BufferedImage embedMessage(byte[] message);

    public abstract byte[] extractMessage();
}
