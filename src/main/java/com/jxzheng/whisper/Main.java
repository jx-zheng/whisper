package com.jxzheng.whisper;

import com.jxzheng.whisper.drivers.CliDriver;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int exitCode = new CliDriver().run(args);
        System.exit(exitCode);
    }
}
