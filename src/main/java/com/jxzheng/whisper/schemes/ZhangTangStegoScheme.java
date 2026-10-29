package com.jxzheng.whisper.schemes;

import com.jxzheng.whisper.carrier.Carrier;
import com.jxzheng.whisper.ops.Progress;

/**
 * Legacy high-capacity LSB-pair scheme. The pixel walk and header MAC are
 * unchanged from Whisper 0.9.
 */
public final class ZhangTangStegoScheme implements StegoScheme {

    public static final String ID = "zhang-tang";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Carrier embed(Carrier cover, byte[] payload, byte[] visitSeed, EmbedOptions options, Progress progress) {
        if (progress != null) {
            progress.stage("Embedding");
        }
        var scheme = new ZhangTangScheme(cover.image(), options.stegoKey());
        return Carrier.of(scheme.embedMessage(payload));
    }

    @Override
    public byte[] extract(Carrier stego, byte[] visitSeed, EmbedOptions options) {
        return new ZhangTangScheme(stego.image(), options.stegoKey()).extractMessage();
    }

}
