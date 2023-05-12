package com.jxzheng.whisper.schemes;

import java.awt.image.BufferedImage;
import java.awt.Point;
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

        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'embedMessage'");
    }

    @Override
    public byte[] extractMessage(byte[] key) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'extractMessage'");
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
