package com.jxzheng.whisper.ops;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;

import com.jxzheng.whisper.carrier.Carrier;
import com.jxzheng.whisper.carrier.CoverConverter;
import com.jxzheng.whisper.carrier.PngCodec;
import com.jxzheng.whisper.carrier.RasterImage;
import com.jxzheng.whisper.schemes.EmbedOptions;
import com.jxzheng.whisper.schemes.RatePreset;
import com.jxzheng.whisper.schemes.adaptive.HillStcScheme;

class IndexedPngTest {

    @Test
    void eightBitPaletteConvertsAndEmbeds() throws Exception {
        int width = 160;
        int height = 120;
        byte[] png = indexedPng(width, height);
        assertTrue(PayloadOps.needsRasterConversion(png));
        byte[] eight = CoverConverter.toEightBitPng(png);
        RasterImage image = PngCodec.decode(eight);
        assertEquals(2, image.colorType);
        assertEquals(0xff, image.samples[0] & 0xff);
        assertEquals(0, image.samples[1] & 0xff);
        assertEquals(0, image.samples[2] & 0xff);

        byte[] payload = new byte[] {9, 8, 7, 6};
        byte[] seed = new byte[32];
        seed[0] = 3;
        HillStcScheme scheme = new HillStcScheme();
        EmbedOptions options = new EmbedOptions(RatePreset.STANDARD, false, "png");
        Carrier stego = scheme.embed(Carrier.raster(image, eight, "png"), payload, seed, options);
        assertArrayEquals(payload, scheme.extract(stego, seed, options));
    }

    private static byte[] indexedPng(int width, int height) {
        byte[] ihdr = new byte[13];
        put32(ihdr, 0, width);
        put32(ihdr, 4, height);
        ihdr[8] = 8;
        ihdr[9] = 3;
        byte[] palette = {(byte) 0xff, 0, 0, 0, 0, (byte) 0xff};
        byte[] raw = new byte[height * (width + 1)];
        for (int y = 0; y < height; y++) {
            int row = y * (width + 1);
            raw[row] = 0;
            for (int x = 0; x < width; x++) {
                raw[row + 1 + x] = (byte) ((x + y) & 1);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        chunk(out, "IHDR", ihdr);
        chunk(out, "PLTE", palette);
        chunk(out, "IDAT", deflate(raw));
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        byte[] buf = new byte[raw.length + 64];
        int n = deflater.deflate(buf);
        deflater.end();
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        write32(out, data.length);
        out.writeBytes(name);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        write32(out, (int) crc.getValue());
    }

    private static void put32(byte[] out, int at, int value) {
        out[at] = (byte) (value >>> 24);
        out[at + 1] = (byte) (value >>> 16);
        out[at + 2] = (byte) (value >>> 8);
        out[at + 3] = (byte) value;
    }

    private static void write32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }
}
