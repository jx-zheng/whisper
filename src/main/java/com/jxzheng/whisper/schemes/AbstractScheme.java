package com.jxzheng.whisper.schemes;

import java.awt.image.BufferedImage;

public abstract class AbstractScheme {

    public abstract BufferedImage embedMessage(String key, String message);
    public abstract String extractMessage(String key);
    
}
