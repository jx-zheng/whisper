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
import java.util.Random;
import java.util.Set;

import com.jxzheng.whisper.exceptions.CorruptOrInvalidStegoImageException;
import com.jxzheng.whisper.exceptions.MessageTooLongException;
import com.jxzheng.whisper.media.PointComparator;
import com.jxzheng.whisper.media.RgbPixel;

public class ZhangTangScheme extends AbstractScheme {

    public ZhangTangScheme(BufferedImage image) {
        super(image);
    }

    @Override
    public BufferedImage embedMessage(String key, String rawMessage) {
        byte[] message = buildEmbeddableMessage(rawMessage);
        final int pixelsNeeded = getNumberOfPixelsNeeded(message.length);
        final Set<Point> selectedPoints = selectPoints(key, pixelsNeeded);
        final List<Point> sortedPoints = sortPoints(selectedPoints);

        List<RgbPixel> modifiedPixels = new ArrayList<RgbPixel>();
        final Point firstPoint = sortedPoints.get(0);
        final RgbPixel firstPixel = new RgbPixel(firstPoint, getPointRgb(firstPoint));
        modifiedPixels.add(firstPixel);

        final int messageBitsLength = message.length * 8;
        int prevPointIndex = 0;
        int bitIndex = 0;
        for (Point point : sortedPoints) {
            Color pointColor = getPointRgb(point);

            List<Integer> newRgb = new ArrayList<Integer>();
            for (String rgb : AbstractScheme.RGB_COLORS) {
                if (bitIndex > messageBitsLength) {
                    break;
                }
                final RgbPixel lastPixel = modifiedPixels.get(prevPointIndex);
                int prevPixelColor;
                int currentPixelColor;
                int msgBit;
                if (rgb.equals("RED")) {
                    prevPixelColor = lastPixel.color().getRed();
                    currentPixelColor = pointColor.getRed();
                    msgBit = getNthBit(message, bitIndex);
                } else if (rgb.equals("GREEN")) {
                    prevPixelColor = lastPixel.color().getGreen();
                    currentPixelColor = pointColor.getGreen();
                    msgBit = getNthBit(message, bitIndex + 1);
                } else if (rgb.equals("BLUE")) {
                    prevPixelColor = lastPixel.color().getBlue();
                    currentPixelColor = pointColor.getBlue();
                    msgBit = getNthBit(message, bitIndex + 2);
                } else {
                    throw new IllegalArgumentException("Invalid color specified");
                }
                int newColor = calculateNewColor(currentPixelColor, prevPixelColor, msgBit);
                newRgb.add(newColor);
                bitIndex++;
            }
            prevPointIndex++;
            Color newColor = new Color(newRgb.get(0), newRgb.get(2), newRgb.get(3));
            modifiedPixels.add(new RgbPixel(point, newColor));
        }
        return buildModifiedImage(modifiedPixels);
    }

    @Override
    public String extractMessage(String key) {
        final int messageLength = extractMessageLength(key);
        final int totalEncodedPixels = getNumberOfPixelsNeeded(messageLength + AbstractScheme.MESSAGE_HEADER_LENGTH);
        final Set<Point> selectedPoints = selectPoints(key, totalEncodedPixels);
        final List<Point> sortedPoints = sortPoints(selectedPoints);
        sortedPoints.subList(0, AbstractScheme.HEADER_POINTS).clear(); // remove header

        byte[] extractedMessage = extractData(sortedPoints, messageLength);
        String message = new String(extractedMessage, StandardCharsets.US_ASCII);
        return message;
    }

    private byte[] buildEmbeddableMessage(String rawMessage) {
        int rawMessageLength = rawMessage.length();
        if(rawMessageLength > AbstractScheme.MAX_RAW_MESSAGE_LENGTH) {
            throw new MessageTooLongException("Message exceeds max message length");
        }
        byte[] embeddableMessage = new byte[rawMessageLength + AbstractScheme.MESSAGE_HEADER_LENGTH];
        byte[] rawMessageBytes = rawMessage.getBytes();
        embeddableMessage[0] = (byte) ((rawMessageLength & 0xFFFF0000) >>> 8);
        embeddableMessage[1] = (byte) (rawMessageLength & 0x0000FFFF);
        embeddableMessage[2] = AbstractScheme.START_OF_TRANSMISSION;
        System.arraycopy(rawMessageBytes, 0, embeddableMessage, 3, rawMessageLength);

        return embeddableMessage;
    }

    private int extractMessageLength(String key) {        
        final Set<Point> headerPoints = selectPoints(key, AbstractScheme.HEADER_POINTS);
        final List<Point> sortedHeaderPoints = sortPoints(headerPoints);
        final byte[] extractedHeader = extractData(sortedHeaderPoints, AbstractScheme.MESSAGE_HEADER_LENGTH);
        if(extractedHeader[3] != AbstractScheme.START_OF_TRANSMISSION) {
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
        for(Point point : sortedPoints) {
            Color pointColor = getPointRgb(point);

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
                extractedData[byteIndex] = (byte) ((extractedData[byteIndex] << bitIndex) | extractedBit);
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
        BufferedImage modifiedImage = super.getImage().getSubimage(0, 0, imageWidth, imageHeight);

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
        byte red = (byte) ((rgba >> 16) & 0xFF);
        byte green = (byte) ((rgba >> 8) & 0xFF);
        byte blue = (byte) (rgba & 0xFF);

        return new Color(red, green, blue);
    }

    // private ArrayList<Byte> getMessageSegments(byte[] message, int segments) {
    // ArrayList<Byte> messageSegments = new ArrayList<Byte>();
    // int messageBitLength = message.length * 8;

    // for (int i = 0; i < segments; i++) {
    // byte currentSegment = 0;
    // int segmentLength = AbstractScheme.USABLE_BITS_PER_PIXEL;
    // int bitNumber = i * segmentLength;
    // int bitIndex = bitNumber % 8;
    // int byteIndex = bitNumber / 8;

    // currentSegment = (byte) ((message[byteIndex] << bitIndex) >> 8 -
    // segmentLength);

    // boolean isOnByteEdge = bitIndex > (8 - segmentLength);
    // if (isOnByteEdge) {
    // int nextByte = message[byteIndex + 1];
    // currentSegment |= (byte) (nextByte);
    // }
    // messageSegments.add(currentSegment);
    // }

    // return messageSegments;
    // }

    private int getNthBit(byte[] bytes, int n) {
        int byteIndex = n / 8;
        if (byteIndex > bytes.length) {
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

    private Set<Point> selectPoints(String seed, int pixelsNeeded) {
        Set<Point> selectedPoints = new HashSet<Point>();

        Random random = new Random();
        random.setSeed(seed.hashCode());

        int imageWidth = super.getImageWidth();
        int imageHeight = super.getImageHeight();

        while (selectedPoints.size() < pixelsNeeded) {
            int x = random.nextInt(imageWidth) + 1;
            int y = random.nextInt(imageHeight) + 1;
            Point pixel = new Point(x, y);

            if (!selectedPoints.contains(pixel)) {
                selectedPoints.add(pixel);
            }
        }
        return selectedPoints;
    }

}
