package com.jxzheng.whisper.modules;

import com.google.inject.AbstractModule;
import com.jxzheng.whisper.encryption.AbstractCipher;
import com.jxzheng.whisper.encryption.AesCipher;
import com.jxzheng.whisper.schemes.AbstractScheme;
import com.jxzheng.whisper.schemes.ZhangTangScheme;

public class CliDriverModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(AbstractScheme.class).to(ZhangTangScheme.class);
        bind(AbstractCipher.class).to(AesCipher.class);
    }
}
