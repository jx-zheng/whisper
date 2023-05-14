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
import com.jxzheng.whisper.media.RgbPoint;

public class ZhangTangScheme extends AbstractScheme {

    public ZhangTangScheme(BufferedImage coverImage) {
        super(coverImage);
    }

    @Override
    public BufferedImage embedMessage(byte[] key, byte[] message) {
        int pixelsNeeded = getNumberOfPixelsNeeded(message);
        Set<Point> selectedPixels = selectPixels(message, pixelsNeeded);
        List<Point> sortedPixels = new ArrayList<Point>(selectedPixels);
        Comparator<Point> pointComparator = new PointComparator();
        Collections.sort(sortedPixels, pointComparator);

        List<RgbPoint> modifiedPixels = new ArrayList<RgbPoint>();

        for(Point pixel : sortedPixels) {
            Color originalColor = getPixelRgb(pixel);

            for(String color : AbstractScheme.RGB_COLORS) {
                switch(color) {
                    case "RED":
                        break;
                    case "GREEN":
                        break;
                    case "BLUE":
                        break;
                }
            }
        }
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'embedMessage'");
    }

    @Override
    public byte[] extractMessage(byte[] key) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'extractMessage'");
    }

    private Color getPixelRgb(Point pixel) {
        int rgba = ZhangTangScheme.coverImage.getRGB(pixel.x, pixel.y);
        byte red = (byte) ((rgba >> 16) & 0xFF);
        byte green = (byte) ((rgba >> 8) & 0xFF);
        byte blue = (byte) (rgba & 0xFF);

        return new Color(red, green, blue);
    }

    private ArrayList<Byte> getMessageSegments(byte[] message, int segments) {
        ArrayList<Byte> messageSegments = new ArrayList<Byte>();
        int messageBitLength = message.length * 8;

        for(int i = 0; i < segments; i++) {
            byte currentSegment = 0;
            int segmentLength = AbstractScheme.USABLE_BITS_PER_PIXEL;
            int bitNumber = i * segmentLength;
            int bitIndex = bitNumber % 8;
            int byteIndex = bitNumber / 8;

            currentSegment = (byte) ((message[byteIndex] << bitIndex) >> 8 - segmentLength);

            boolean isOnByteEdge = bitIndex > (8 - segmentLength);
            if(isOnByteEdge) {
                int nextByte = message[byteIndex + 1];
                currentSegment |= (byte) (nextByte);
            }
            messageSegments.add(currentSegment);
        }

        return messageSegments;
    }

    private int getNthBit(byte[] bytes, int n) {
        int byteIndex = n / 8;
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

    private Set<Point> selectPixels(byte[] seed, int pixelsNeeded) {
        Set<Point> selectedPixels = new HashSet<Point>();

        Random random = new Random();
        random.setSeed(seed.hashCode());

        int imageWidth = ZhangTangScheme.coverImage.getWidth();
        int imageHeight = ZhangTangScheme.coverImage.getHeight();

        while(selectedPixels.size() < pixelsNeeded) {
            int x = random.nextInt(imageWidth) + 1;
            int y = random.nextInt(imageHeight) + 1;
            Point pixel = new Point(x, y);
            
            if(!selectedPixels.contains(pixel)) {
                selectedPixels.add(pixel);
            }
        }
        return selectedPixels;
    }
    
}
