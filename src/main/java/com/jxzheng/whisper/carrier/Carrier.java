package com.jxzheng.whisper.carrier;

import java.awt.image.BufferedImage;

/**
 * A decoded cover. Phase 1 stores the {@link BufferedImage} Zhang–Tang already
 * uses. Adaptive schemes add sample and coefficient planes on this type.
 */
public final class Carrier {

    private final BufferedImage image;

    private Carrier(BufferedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        this.image = image;
    }

    public static Carrier of(BufferedImage image) {
        return new Carrier(image);
    }

    public BufferedImage image() {
        return image;
    }

    public int width() {
        return image.getWidth();
    }

    public int height() {
        return image.getHeight();
    }
}
