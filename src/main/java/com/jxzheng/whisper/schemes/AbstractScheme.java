package com.jxzheng.whisper.schemes;

import java.awt.image.BufferedImage;
import java.util.List;

public abstract class AbstractScheme {

    public static final int USABLE_BITS_PER_PIXEL = 3;
    public static final int BITS_PER_MSG_CHARACTER = 8;
    public static final List<String> RGB_COLORS = List.of("RED", "GREEN", "BLUE");

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
