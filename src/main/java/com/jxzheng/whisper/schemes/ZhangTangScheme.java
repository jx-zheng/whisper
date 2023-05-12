package com.jxzheng.whisper.schemes;

import java.awt.image.BufferedImage;
// import java.awt.Point;

public class ZhangTangScheme extends AbstractScheme {

    public ZhangTangScheme(BufferedImage coverImage) {
        super(coverImage);
    }

    @Override
    public BufferedImage embedMessage(String key, String message) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'embedMessage'");
    }

    @Override
    public String extractMessage(String key) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'extractMessage'");
    }
    
}
