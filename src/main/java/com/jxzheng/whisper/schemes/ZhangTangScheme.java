package com.jxzheng.whisper.schemes;

import java.awt.image.BufferedImage;
import java.util.List;
import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import com.jxzheng.whisper.media.PointComparator;
import com.jxzheng.whisper.media.RgbPixel;

public class ZhangTangScheme extends AbstractScheme {

    public ZhangTangScheme(BufferedImage coverImage) {
        super(coverImage);
    }

    @Override
    public BufferedImage embedMessage(byte[] key, byte[] message) {
        final int pixelsNeeded = getNumberOfPixelsNeeded(message);
        final Set<Point> selectedPoints = selectPoints(message, pixelsNeeded);
        final List<Point> sortedPoints = new ArrayList<Point>(selectedPoints);
        final Comparator<Point> pointComparator = new PointComparator();
        Collections.sort(sortedPoints, pointComparator);

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
                if(bitIndex > messageBitsLength) {
                    break;
                }
                final RgbPixel lastPixel = modifiedPixels.get(prevPointIndex);
                int prevPixelColor;
                int currentPixelColor;
                int msgBit;
                if(rgb.equals("RED")) {
                    prevPixelColor = lastPixel.color().getRed();
                    currentPixelColor = pointColor.getRed();
                    msgBit = getNthBit(message, bitIndex);
                }
                else if(rgb.equals("GREEN")) {
                    prevPixelColor = lastPixel.color().getGreen();
                    currentPixelColor = pointColor.getGreen();
                    msgBit = getNthBit(message, bitIndex + 1);
                }
                else if(rgb.equals("BLUE")) {
                    prevPixelColor = lastPixel.color().getBlue();
                    currentPixelColor = pointColor.getBlue();
                    msgBit = getNthBit(message, bitIndex + 2);
                }
                else {
                    throw new IllegalArgumentException("Invalid color specified");
                }
                int newColor = calculateNewColor(currentPixelColor, prevPixelColor, msgBit);
                newRgb.add(newColor);
                bitIndex++;
            }
            prevPointIndex++;
        }
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'embedMessage'");
    }

    @Override
    public byte[] extractMessage(byte[] key) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'extractMessage'");
    }

    private int calculateNewColor(int currentColor, int prevColor, int msgBit) {
        int prevColorLsb = prevColor & 1;
        int currentColorLsb = currentColor & 1;
        int newColor = currentColor + msgBit - (prevColorLsb + currentColorLsb) % 2;
        if(newColor > 255) {
            newColor -= 2;
        }
        if(newColor < 0) {
            newColor += 2;
        }
        return newColor;
    }

    private Color getPointRgb(Point point) {
        int rgba = ZhangTangScheme.coverImage.getRGB(point.x, point.y);
        byte red = (byte) ((rgba >> 16) & 0xFF);
        byte green = (byte) ((rgba >> 8) & 0xFF);
        byte blue = (byte) (rgba & 0xFF);

        return new Color(red, green, blue);
    }

    // private ArrayList<Byte> getMessageSegments(byte[] message, int segments) {
    //     ArrayList<Byte> messageSegments = new ArrayList<Byte>();
    //     int messageBitLength = message.length * 8;

    //     for (int i = 0; i < segments; i++) {
    //         byte currentSegment = 0;
    //         int segmentLength = AbstractScheme.USABLE_BITS_PER_PIXEL;
    //         int bitNumber = i * segmentLength;
    //         int bitIndex = bitNumber % 8;
    //         int byteIndex = bitNumber / 8;

    //         currentSegment = (byte) ((message[byteIndex] << bitIndex) >> 8 - segmentLength);

    //         boolean isOnByteEdge = bitIndex > (8 - segmentLength);
    //         if (isOnByteEdge) {
    //             int nextByte = message[byteIndex + 1];
    //             currentSegment |= (byte) (nextByte);
    //         }
    //         messageSegments.add(currentSegment);
    //     }

    //     return messageSegments;
    // }

    private int getNthBit(byte[] bytes, int n) {
        int byteIndex = n / 8;
        if(byteIndex > bytes.length) {
            throw new IllegalArgumentException("Bit requested is out of bounds");
        }
        int bitIndex = n % 8;
        byte b = bytes[byteIndex];
        return (b >> (7 - bitIndex)) & 1;
    }

    private int getNumberOfPixelsNeeded(byte[] message) {
        int bitsInMessage = message.length * AbstractScheme.BITS_PER_MSG_CHARACTER;
        float usableBitsPerPixel = (float) AbstractScheme.USABLE_BITS_PER_PIXEL;
        int pixelsNeeded = (int) Math.ceil(bitsInMessage / usableBitsPerPixel);
        return pixelsNeeded;
    }

    private Set<Point> selectPoints(byte[] seed, int pixelsNeeded) {
        Set<Point> selectedPoints = new HashSet<Point>();

        Random random = new Random();
        random.setSeed(seed.hashCode());

        int imageWidth = ZhangTangScheme.coverImage.getWidth();
        int imageHeight = ZhangTangScheme.coverImage.getHeight();

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
