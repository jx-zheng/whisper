package com.jxzheng.whisper.payload;

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

/** Re-encodes a hidden image as JPEG so it fits the cover. The cover itself is not touched. */
public final class ImageFit {

    public record Result(byte[] bytes, boolean reencoded, int width, int height) {
    }

    private ImageFit() {
    }

    public static Result fit(byte[] original, int quality, int maxEdge) throws IOException {
        if (quality < 40 || quality > 95) {
            throw new IllegalArgumentException("JPEG quality must be from 40 to 95");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));
        if (image == null) {
            throw new IOException("Could not read the image to fit");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (maxEdge > 0 && Math.max(width, height) > maxEdge) {
            double scale = maxEdge / (double) Math.max(width, height);
            width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.drawImage(image, 0, 0, width, height, null);
            g.dispose();
            image = scaled;
        } else if (image.getColorModel().hasAlpha()) {
            BufferedImage flat = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = flat.createGraphics();
            g.drawImage(image, 0, 0, null);
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
        return new Result(bytes.toByteArray(), true, width, height);
    }
}
