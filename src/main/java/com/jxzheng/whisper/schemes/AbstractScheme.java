package com.jxzheng.whisper.schemes;

import java.awt.image.BufferedImage;

public abstract class AbstractScheme {

    public static final int USABLE_BITS_PER_PIXEL = 3;
    public static final int BITS_PER_MSG_CHARACTER = 8;

    protected static BufferedImage coverImage;

    public AbstractScheme(BufferedImage coverImage) {
        AbstractScheme.coverImage = coverImage;
    }

    public static BufferedImage getCoverImage() {
        return coverImage;
    }

    public abstract BufferedImage embedMessage(byte[] key, byte[] message);
    public abstract byte[] extractMessage(byte[] key);

}
