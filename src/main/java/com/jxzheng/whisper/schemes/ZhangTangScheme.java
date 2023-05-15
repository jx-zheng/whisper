package com.jxzheng.whisper.schemes;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

import com.jxzheng.whisper.exceptions.CorruptOrInvalidStegoImageException;
import com.jxzheng.whisper.exceptions.MessageTooLongException;
import com.jxzheng.whisper.media.PointComparator;
import com.jxzheng.whisper.media.RgbPixel;

public class ZhangTangScheme extends AbstractScheme {

    public ZhangTangScheme(BufferedImage image, String key) {
        super(image, key);
    }

    @Override
    public BufferedImage embedMessage(String rawMessage) {
        byte[] payload = buildEmbeddablePayload(rawMessage);
        final int msgPixelsNeeded = getNumberOfPixelsNeeded(rawMessage.length());
        List<Point> payloadPoints = getHeaderPoints();
        final Set<Point> selectedMsgPoints = selectPoints(msgPixelsNeeded);
        final List<Point> sortedMsgPoints = sortPoints(selectedMsgPoints);
        payloadPoints.addAll(sortedMsgPoints);

        List<RgbPixel> modifiedPixels = new ArrayList<RgbPixel>();
        final Point firstPoint = payloadPoints.get(0);
        final RgbPixel firstPixel = new RgbPixel(firstPoint, getPointRgb(firstPoint));
        modifiedPixels.add(firstPixel);

        final int payloadBitsLength = payload.length * 8;
        int prevPointIndex = 0;
        int bitIndex = 0;
        for (int i = 1; i < payloadPoints.size(); i++) {
            Point point = payloadPoints.get(i);
            Color pointColor = getPointRgb(point);

            List<Integer> newRgb = new ArrayList<Integer>();
            for (String rgb : AbstractScheme.RGB_COLORS) {
                if (bitIndex == payloadBitsLength) {
                    break;
                }
                final RgbPixel lastPixel = modifiedPixels.get(prevPointIndex);
                int prevPixelColor;
                int currentPixelColor;
                if (rgb.equals("RED")) {
                    prevPixelColor = lastPixel.color().getRed();
                    currentPixelColor = pointColor.getRed();
                } else if (rgb.equals("GREEN")) {
                    prevPixelColor = lastPixel.color().getGreen();
                    currentPixelColor = pointColor.getGreen();
                } else if (rgb.equals("BLUE")) {
                    prevPixelColor = lastPixel.color().getBlue();
                    currentPixelColor = pointColor.getBlue();
                } else {
                    throw new IllegalArgumentException("Invalid color specified");
                }
                final int msgBit = getNthBit(payload, bitIndex);
                final int newColor = calculateNewColor(currentPixelColor, prevPixelColor, msgBit);
                newRgb.add(newColor);
                bitIndex++;
            }
            prevPointIndex++;
            switch(newRgb.size()) {
                case 1:
                    newRgb.add(pointColor.getGreen());
                case 2:
                    newRgb.add(pointColor.getBlue());
                    break;
            }
            Color newColor = new Color(newRgb.get(0), newRgb.get(1), newRgb.get(2));
            modifiedPixels.add(new RgbPixel(point, newColor));
        }

        return buildModifiedImage(modifiedPixels);
    }

    @Override
    public String extractMessage() {
        final int messageLength = extractMessageLength();
        final int msgEncodedPixels = getNumberOfPixelsNeeded(messageLength);
        super.restartRandomSequence();
        List<Point> payloadPoints = getHeaderPoints();
        final Set<Point> selectedMsgPoints = selectPoints(msgEncodedPixels);
        final List<Point> sortedMsgPoints = sortPoints(selectedMsgPoints);
        payloadPoints.addAll(sortedMsgPoints);
        payloadPoints.subList(0, AbstractScheme.HEADER_POINTS - 1).clear();

        byte[] extractedMessage = extractData(payloadPoints, messageLength);
        String message = new String(extractedMessage, StandardCharsets.US_ASCII);
        return message;
    }

    private List<Point> getHeaderPoints() {
        Set<Point> selectedPoints = selectPoints(AbstractScheme.HEADER_POINTS);
        return sortPoints(selectedPoints);
    }

    private byte[] buildEmbeddablePayload(String rawMessage) {
        int rawMessageLength = rawMessage.length();
        if(rawMessageLength > AbstractScheme.MAX_RAW_MESSAGE_LENGTH) {
            throw new MessageTooLongException("Message exceeds max message length");
        }
        byte[] payload = new byte[rawMessageLength + AbstractScheme.MESSAGE_HEADER_LENGTH];
        byte[] rawMessageBytes = rawMessage.getBytes(StandardCharsets.US_ASCII);
        payload[0] = (byte) ((rawMessageLength & 0xFFFF0000) >>> 8);
        payload[1] = (byte) (rawMessageLength & 0x0000FFFF);
        payload[2] = AbstractScheme.START_OF_TRANSMISSION;
        System.arraycopy(rawMessageBytes, 0, payload, 3, rawMessageLength);

        return payload;
    }

    private int extractMessageLength() {        
        final List<Point> headerPoints = getHeaderPoints();
        final byte[] extractedHeader = extractData(headerPoints, AbstractScheme.MESSAGE_HEADER_LENGTH);
        if(extractedHeader[2] != AbstractScheme.START_OF_TRANSMISSION) {
            throw new CorruptOrInvalidStegoImageException("Couldn't find STX byte");
        }
        final int messageLength = extractedHeader[0] << 8 | extractedHeader[1];
        return messageLength;
    }

    private byte[] extractData(List<Point> sortedPoints, int bytesExpected) {
        byte[] extractedData = new byte[bytesExpected];
        List<Color> pixelColors = new ArrayList<Color>();
        Color firstPointColor = getPointRgb(sortedPoints.get(0));
        pixelColors.add(firstPointColor);

        int prevPointIndex = 0;
        int bitIndex = 0;
        int byteIndex = 0;
        for(int i = 1; i < sortedPoints.size(); i++) {
            Point point = sortedPoints.get(i);
            Color pointColor = getPointRgb(point);
            pixelColors.add(pointColor);

            for(String rgb : AbstractScheme.RGB_COLORS) {
                if(bitIndex == 8) {
                    bitIndex = 0;
                    byteIndex++;
                    if(byteIndex == bytesExpected) {
                        break;
                    }
                }
                final Color lastPixel = pixelColors.get(prevPointIndex);
                int prevPixelColor;
                int currentPixelColor;
                if(rgb.equals("RED")) {
                    prevPixelColor = lastPixel.getRed();
                    currentPixelColor = pointColor.getRed();
                }
                else if(rgb.equals("GREEN")) {
                    prevPixelColor = lastPixel.getGreen();
                    currentPixelColor = pointColor.getGreen();
                }
                else if(rgb.equals("BLUE")) {
                    prevPixelColor = lastPixel.getBlue();
                    currentPixelColor = pointColor.getBlue();
                }
                else {
                    throw new IllegalArgumentException("Invalid color specified");
                }
                final int prevColorLsb = prevPixelColor & 1;
                final int currentColorLsb = currentPixelColor & 1;
                final int extractedBit = (prevColorLsb + currentColorLsb) % 2;
                extractedData[byteIndex] = (byte) ((extractedData[byteIndex] << 1) | extractedBit);
                // TODO: fix last pixel byte not shifting all the way to the correct left position due to break early
                bitIndex++;
            }
            prevPointIndex++;
        }
        return extractedData;
    }

    private List<Point> sortPoints(Set<Point> points) {
        List<Point> sortedPoints = new ArrayList<Point>(points);
        final Comparator<Point> pointComparator = new PointComparator();
        Collections.sort(sortedPoints, pointComparator);
        return sortedPoints;
    }

    private BufferedImage buildModifiedImage(List<RgbPixel> modifiedPixels) {
        final int imageWidth = super.getImageWidth();
        final int imageHeight = super.getImageHeight();
        BufferedImage modifiedImage = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_3BYTE_BGR);
        modifiedImage.getGraphics().drawImage(super.getImage(), 0, 0, null);

        for(RgbPixel pixel : modifiedPixels) {
            modifiedImage.setRGB(pixel.point().x, pixel.point().y, pixel.color().getRGB());
        }

        return modifiedImage;
    }

    private int calculateNewColor(int currentColor, int prevColor, int msgBit) {
        int prevColorLsb = prevColor & 1;
        int currentColorLsb = currentColor & 1;
        int newColor = currentColor + msgBit - (prevColorLsb + currentColorLsb) % 2;
        if (newColor > 255) {
            newColor -= 2;
        }
        if (newColor < 0) {
            newColor += 2;
        }
        return newColor;
    }

    private Color getPointRgb(Point point) {
        int rgba = super.getImage().getRGB(point.x, point.y);
        int red = (rgba >>> 16) & 0xFF;
        int green = (rgba >>> 8) & 0xFF;
        int blue = rgba & 0xFF;

        return new Color(red, green, blue);
    }

    private int getNthBit(byte[] bytes, int n) {
        int byteIndex = n / 8;
        if (byteIndex >= bytes.length) {
            throw new IllegalArgumentException("Bit requested is out of bounds");
        }
        int bitIndex = n % 8;
        byte b = bytes[byteIndex];
        return (b >> (7 - bitIndex)) & 1;
    }

    private int getNumberOfPixelsNeeded(int messageLength) {
        int bitsInMessage = messageLength * AbstractScheme.BITS_PER_MSG_CHARACTER;
        float usableBitsPerPixel = (float) AbstractScheme.USABLE_BITS_PER_PIXEL;
        int pixelsNeeded = (int) Math.ceil(bitsInMessage / usableBitsPerPixel);
        return pixelsNeeded;
    }

    private Set<Point> selectPoints(int pixelsNeeded) {
        Set<Point> selectedPoints = new HashSet<Point>();

        int imageWidth = super.getImageWidth();
        int imageHeight = super.getImageHeight();

        while (selectedPoints.size() < pixelsNeeded) {
            int x = super.getRandom().nextInt(imageWidth) + 1;
            int y = super.getRandom().nextInt(imageHeight) + 1;
            Point pixel = new Point(x, y);

            if (!selectedPoints.contains(pixel)) {
                selectedPoints.add(pixel);
            }
        }
        return selectedPoints;
    }

}
