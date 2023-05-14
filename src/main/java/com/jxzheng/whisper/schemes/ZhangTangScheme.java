package com.jxzheng.whisper.schemes;

import java.awt.image.BufferedImage;
import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class ZhangTangScheme extends AbstractScheme {

    public ZhangTangScheme(BufferedImage coverImage) {
        super(coverImage);
    }

    @Override
    public BufferedImage embedMessage(byte[] key, byte[] message) {
        int pixelsNeeded = getNumberOfPixelsNeeded(message);
        Set<Point> selectedPixels = selectPixels(message, pixelsNeeded);

        for(Point pixel : selectedPixels) {
            int rgb = ZhangTangScheme.coverImage.getRGB(pixel.x, pixel.y);

            byte red = (byte) ((rgb >> 16) & 0xFF);
            byte green = (byte) ((rgb >> 8) & 0xFF);
            byte blue = (byte) (rgb & 0xFF);
        }
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'embedMessage'");
    }

    @Override
    public byte[] extractMessage(byte[] key) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'extractMessage'");
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
