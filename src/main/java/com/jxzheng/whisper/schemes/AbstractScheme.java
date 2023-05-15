package com.jxzheng.whisper.schemes;

import java.awt.image.BufferedImage;
import java.util.List;

public abstract class AbstractScheme {

    public static final int USABLE_BITS_PER_PIXEL = 3;
    public static final int BITS_PER_MSG_CHARACTER = 8;
    public static final byte START_OF_TRANSMISSION = 2;
    public static final int MAX_RAW_MESSAGE_LENGTH = 65535;
    public static final int MESSAGE_HEADER_LENGTH = 3;
    public static final List<String> RGB_COLORS = List.of("RED", "GREEN", "BLUE");

    private BufferedImage image;
    private int imageWidth;
    private int imageHeight;

    public AbstractScheme(BufferedImage image) {
        this.image = image;
        this.imageWidth = image.getWidth();
        this.imageHeight = image.getHeight();
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

    public abstract BufferedImage embedMessage(String key, String message);

    public abstract String extractMessage(String key);

}
