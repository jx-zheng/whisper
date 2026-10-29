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
 * Re-encodes a hidden image as JPEG until its bytes fit the cover's content room.
 * The cover itself is not touched. Callers pass a room that already excludes the
 * payload-frame header and the envelope overhead.
 */
public final class ImageFit {

    public record Result(byte[] bytes, boolean reencoded, int width, int height) {
    }

    private ImageFit() {
    }

    /**
     * Starts at {@code quality} and {@code maxEdge} (0 keeps the original edge)
     * and steps both down until {@code content.length} is within {@code capacity}.
     * {@code capacity} is the content room already returned to the user: the
     * 46-byte frame header, name, and MIME have been subtracted, and
     * {@code ParityFraming.userBudget} has already removed the envelope.
     */
    public static Result fit(byte[] original, int quality, int maxEdge, int capacity) throws IOException {
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
        while (true) {
            Result encoded = encode(source, q, edge);
            int size = encoded.bytes().length;
            if (size <= capacity) {
                return encoded;
            }
            if (capacity > 0 && size > (long) capacity * 2 && edge > floor) {
                int next = shrinkToward(edge, floor, size, capacity);
                if (next < edge) {
                    edge = next;
                    continue;
                }
            }
            if (q > 40) {
                q = Math.max(40, q - 5);
                continue;
            }
            if (edge <= floor) {
                throw tooBig(edge, size, capacity);
            }
            int next = Math.max(floor, (edge * 3) / 4);
            if (next >= edge) {
                next = Math.max(floor, edge - 1);
            }
            if (next >= edge) {
                throw tooBig(edge, size, capacity);
            }
            edge = next;
            q = quality;
        }
    }

    /** Jump the long edge toward the content room when the JPEG is still far too large. */
    private static int shrinkToward(int edge, int floor, int size, int capacity) {
        double ratio = Math.sqrt((double) capacity / size);
        int next = (int) Math.floor(edge * ratio);
        if (next >= edge) {
            next = (edge * 3) / 4;
        }
        return Math.max(floor, next);
    }

    private static IllegalArgumentException tooBig(int edge, int size, int capacity) {
        return new IllegalArgumentException(
                "Image does not fit this cover even at quality 40 and longest edge " + edge
                        + " (image is " + size + " bytes, capacity is " + capacity + ").");
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
