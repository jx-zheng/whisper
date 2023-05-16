package com.jxzheng.whisper;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.jxzheng.whisper.drivers.CliDriver;
import com.jxzheng.whisper.modules.CliDriverModule;

public class Main 
{
    public static void main( String[] args )
    {
        Injector injector = Guice.createInjector(new CliDriverModule());

        CliDriver driver = injector.getInstance(CliDriver.class);
        driver.parseArgs(args);

    }
}
