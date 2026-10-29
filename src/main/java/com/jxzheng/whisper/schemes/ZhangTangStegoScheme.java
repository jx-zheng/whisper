package com.jxzheng.whisper.schemes;

import java.util.Optional;

import com.jxzheng.whisper.carrier.Carrier;
import com.jxzheng.whisper.carrier.CarrierProbe;
import com.jxzheng.whisper.ops.Progress;
import com.jxzheng.whisper.ops.StegoOperations;

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
    public SchemeInfo info() {
        return new SchemeInfo(ID, "Legacy (high capacity)", "Easier to detect", "bytes");
    }

    @Override
    public boolean accepts(CarrierProbe probe) {
        if (probe == null) {
            return false;
        }
        if (startsWithPng(probe.prefix()) || startsWithBmp(probe.prefix())) {
            return true;
        }
        String name = probe.name() == null ? "" : probe.name().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".bmp");
    }

    @Override
    public Capacity estimate(Carrier cover, EmbedOptions options) {
        int max = StegoOperations.maxPayloadBytes(cover.width(), cover.height());
        if (max < 0) {
            return Capacity.none("bytes");
        }
        return new Capacity(max, max, "bytes", 3.0);
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

    @Override
    public Optional<byte[]> publicSalt(Carrier stego, RatePreset preset) {
        return Optional.empty();
    }

    private static boolean startsWithPng(byte[] prefix) {
        return prefix != null && prefix.length >= 8
                && prefix[0] == (byte) 0x89 && prefix[1] == 'P' && prefix[2] == 'N' && prefix[3] == 'G';
    }

    private static boolean startsWithBmp(byte[] prefix) {
        return prefix != null && prefix.length >= 2 && prefix[0] == 'B' && prefix[1] == 'M';
    }
}
