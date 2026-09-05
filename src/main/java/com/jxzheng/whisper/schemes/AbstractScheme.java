package com.jxzheng.whisper.schemes;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Random;

/**
 * Base type for image steganography schemes.
 * Subclasses embed and extract arbitrary byte payloads in a cover image.
 */
public abstract class AbstractScheme {

    public static final int USABLE_BITS_PER_PIXEL = 3;
    public static final int BITS_PER_BYTE = 8;
    /**
     * Header layout: 2-byte big-endian length + 2-byte truncated HMAC tag.
     */
    public static final int MESSAGE_HEADER_LENGTH = 4;
    public static final int MAX_PAYLOAD_LENGTH = 65_535;
    /**
     * One reference pixel plus enough pixels to carry the header
     * ({@code ceil(headerBits / usableBitsPerPixel)}).
     */
    public static final int HEADER_POINTS =
            1 + (MESSAGE_HEADER_LENGTH * BITS_PER_BYTE + USABLE_BITS_PER_PIXEL - 1)
                    / USABLE_BITS_PER_PIXEL;
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
        this.random = createKeyedRandom(key);
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

    protected String getKey() {
        return key;
    }

    public void restartRandomSequence() {
        this.random = createKeyedRandom(key);
    }

    /**
     * Seeds {@link Random} from SHA-256(passphrase) rather than
     * {@link String#hashCode()}, which only has 32 bits and collides easily.
     * Prefer the HMAC stream PRNG from the stronger-prng follow-up for production use.
     */
    private static Random createKeyedRandom(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            long seed = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
            return new Random(seed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public abstract BufferedImage embedMessage(byte[] message);

    public abstract byte[] extractMessage();
}
