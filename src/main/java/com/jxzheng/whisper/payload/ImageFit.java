package com.jxzheng.whisper.payload;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Re-encodes a hidden image as JPEG until the framed payload fits the cover.
 * The cover itself is not touched.
 */
public final class ImageFit {

    public record Result(byte[] bytes, boolean reencoded, int width, int height) {
    }

    private ImageFit() {
    }

    /**
     * Starts at {@code quality} and {@code maxEdge} (0 keeps the original edge)
     * and steps both down until {@code content + 46 + name + mime} is within
     * {@code capacity}.
     */
    public static Result fit(byte[] original, int quality, int maxEdge, int capacity, String name) throws IOException {
        if (quality < 40 || quality > 95) {
            throw new IllegalArgumentException("JPEG quality must be from 40 to 95");
        }
        if (capacity < 0) {
            throw new IllegalArgumentException("Could not estimate this cover's capacity, so the image was not fitted.");
        }
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(original));
        if (source == null) {
            throw new IOException("Could not read the image to fit");
        }
        int natural = Math.max(source.getWidth(), source.getHeight());
        int edge = maxEdge > 0 ? Math.min(maxEdge, natural) : natural;
        int floor = Math.min(32, natural);
        int q = quality;
        Result smallest = null;
        while (true) {
            Result encoded = encode(source, q, edge);
            smallest = encoded;
            if (frameLength(encoded.bytes(), name) <= capacity) {
                return encoded;
            }
            if (q > 40) {
                q = Math.max(40, q - 5);
                continue;
            }
            if (edge <= floor) {
                int framed = frameLength(encoded.bytes(), name);
                throw new IllegalArgumentException(
                        "Image does not fit this cover even at quality 40 and longest edge " + edge
                                + " (frame is " + framed + " bytes, capacity is " + capacity + ").");
            }
            int next = Math.max(floor, (edge * 3) / 4);
            if (next >= edge) {
                next = Math.max(floor, edge - 1);
            }
            if (next >= edge) {
                int framed = frameLength(smallest.bytes(), name);
                throw new IllegalArgumentException(
                        "Image does not fit this cover even at quality 40 and longest edge " + edge
                                + " (frame is " + framed + " bytes, capacity is " + capacity + ").");
            }
            edge = next;
            q = quality;
        }
    }

    private static int frameLength(byte[] content, String name) {
        return PayloadFrame.build(PayloadKind.IMAGE, name, "image/jpeg", content, true).bytes().length;
    }

    private static Result encode(BufferedImage source, int quality, int maxEdge) throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage image = source;
        if (maxEdge > 0 && Math.max(width, height) > maxEdge) {
            double scale = maxEdge / (double) Math.max(width, height);
            width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
            g.dispose();
            image = scaled;
        } else if (image.getColorModel().hasAlpha()) {
            BufferedImage flat = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = flat.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, null);
            g.dispose();
            image = flat;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality / 100f);
        writer.setOutput(new MemoryCacheImageOutputStream(bytes));
        writer.write(null, new IIOImage(image, null, null), param);
        writer.dispose();
        return new Result(bytes.toByteArray(), true, image.getWidth(), image.getHeight());
    }
}
