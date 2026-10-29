package com.jxzheng.whisper.gui;

import java.nio.file.Path;

@FunctionalInterface
interface StegoHandoff {

    void openExtract(Path stego, String key, boolean decrypt, String scheme, String cipher);
}
