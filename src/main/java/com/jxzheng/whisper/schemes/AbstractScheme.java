package com.jxzheng.whisper.schemes;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

public abstract class AbstractScheme {

    public static final int USABLE_BITS_PER_PIXEL = 3;
    public static final int BITS_PER_MSG_CHARACTER = 8;
    public static final byte START_OF_TRANSMISSION = 2;
    public static final int MAX_RAW_MESSAGE_LENGTH = 65535;
    public static final int MESSAGE_HEADER_LENGTH = 3;
    public static final int HEADER_POINTS = 1 + (MESSAGE_HEADER_LENGTH * 8) / USABLE_BITS_PER_PIXEL;
    public static final List<String> RGB_COLORS = List.of("RED", "GREEN", "BLUE");

    private BufferedImage image;
    private int imageWidth;
    private int imageHeight;
    private Random random;
    private String key;

    public AbstractScheme(BufferedImage image, String key) {
        this.image = image;
        this.imageWidth = image.getWidth();
        this.imageHeight = image.getHeight();
        this.random = new Random(key.hashCode());
        this.key = key;
    }

    public BufferedImage getImage() {
        return this.image;
    }

    public int getImageWidth() {
        return this.imageWidth;
    }

    public int getImageHeight() {
        return this.imageHeight;
    }

    public Random getRandom() {
        return this.random;
    }

    public void restartRandomSequence() {
        this.random = new Random(key.hashCode());
    }

    public abstract BufferedImage embedMessage(String message);

    public abstract String extractMessage();

}
