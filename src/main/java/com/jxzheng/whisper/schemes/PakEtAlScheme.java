package com.jxzheng.whisper.schemes;

import java.awt.image.BufferedImage;

public class PakEtAlScheme extends AbstractScheme {

    public PakEtAlScheme(BufferedImage coverImage) {
        super(coverImage);
    }

    @Override
    public BufferedImage embedMessage(byte[] key, byte[] message) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'embedMessage'");
    }

    @Override
    public byte[] extractMessage(byte[] key) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'extractMessage'");
    }

}
