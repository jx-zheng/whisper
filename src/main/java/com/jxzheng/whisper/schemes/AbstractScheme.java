package com.jxzheng.whisper.schemes;

import java.awt.image.BufferedImage;

public abstract class AbstractScheme {

    private static BufferedImage coverImage;

    public AbstractScheme(BufferedImage coverImage) {
        AbstractScheme.coverImage = coverImage;
    }

    public static BufferedImage getCoverImage() {
        return coverImage;
    }

    public abstract BufferedImage embedMessage(String key, String message);
    public abstract String extractMessage(String key);

}
