package com.jxzheng.whisper;

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.jxzheng.whisper.drivers.CliDriver;
import com.jxzheng.whisper.encryption.AesCipher;
import com.jxzheng.whisper.exceptions.EncryptionException;
import com.jxzheng.whisper.modules.CliDriverModule;
import com.jxzheng.whisper.schemes.AbstractScheme;
import com.jxzheng.whisper.schemes.ZhangTangScheme;

public class Main 
{
    public static void main( String[] args )
    {
        Injector injector = Guice.createInjector(new CliDriverModule());

        CliDriver driver = injector.getInstance(CliDriver.class);
        driver.parseArgs(args);

    }
}
