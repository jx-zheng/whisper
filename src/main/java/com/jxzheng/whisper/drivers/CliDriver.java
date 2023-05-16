package com.jxzheng.whisper.drivers;

import org.apache.commons.cli.*;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.jxzheng.whisper.encryption.AbstractCipher;
import com.jxzheng.whisper.schemes.AbstractScheme;

@Singleton
public class CliDriver {

    private final AbstractScheme scheme;
    private final AbstractCipher cipher;
    private final Options options;
    private final CommandLineParser parser;

    @Inject
    public CliDriver(AbstractScheme scheme, AbstractCipher cipher) {
        this.scheme = scheme;
        this.cipher = cipher;

        this.options = new Options();
        options.addOption("o", "output-file", true, "File to output to");
        options.addOption("m", "message", true, "The message to embed");
        options.addOption("s", "scheme", true, "The steganography scheme to use");
        options.addOption("c", "cipher", true, "Encrypt message with the specified cipher");
        options.addOption("k", "key", true, "The steganographic key (and cipher key if applicable)");
        options.addOption("h", "help", false, "Show usage information");

        this.parser = new DefaultParser();
    }

    public void parseArgs(String[] args) {
        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cl = parser.parse(this.options, args);
        } catch (ParseException e) {
            e.printStackTrace();
        }
    
    }


}
