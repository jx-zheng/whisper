package com.jxzheng.whisper.ops;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;

import com.jxzheng.whisper.carrier.Carrier;
import com.jxzheng.whisper.carrier.PngCodec;
import com.jxzheng.whisper.carrier.RasterImage;
import com.jxzheng.whisper.schemes.EmbedOptions;
import com.jxzheng.whisper.schemes.RatePreset;
import com.jxzheng.whisper.schemes.adaptive.HillStcScheme;

class TruecolorPlteTest {

    @Test
    void suggestedPaletteIsDroppedAndHillEmbeds() throws Exception {
        int width = 160;
        int height = 120;
        byte[] png = rgbWithPlte(width, height);
        assertFalse(PayloadOps.needsRasterConversion(png));
        RasterImage decoded = PngCodec.decode(png);
        assertEquals(2, decoded.colorType);
        assertEquals(0x11, decoded.samples[0] & 0xff);
        assertEquals(0x22, decoded.samples[1] & 0xff);
        assertEquals(0x33, decoded.samples[2] & 0xff);
        assertTrue(decoded.ancillary.stream().noneMatch(chunk -> "PLTE".equals(chunk.type())));

        RasterImage viaCover = PayloadOps.coverRaster(png);
        assertEquals(2, viaCover.colorType);
        assertEquals(0x11, viaCover.samples[0] & 0xff);

        byte[] payload = new byte[] {9, 8, 7, 6};
        byte[] seed = new byte[32];
        seed[0] = 4;
        HillStcScheme scheme = new HillStcScheme();
        EmbedOptions options = new EmbedOptions(RatePreset.STANDARD, false, "png");
        Carrier stego = scheme.embed(Carrier.raster(decoded, png, "png"), payload, seed, options);
        assertArrayEquals(payload, scheme.extract(stego, seed, options));
    }

    @Test
    void indexedPaletteStillRejectedByDecoder() {
        byte[] png = indexedPixel();
        assertTrue(PayloadOps.needsRasterConversion(png));
        assertThrows(IllegalArgumentException.class, () -> PngCodec.decode(png));
    }

    private static byte[] rgbWithPlte(int width, int height) {
        byte[] ihdr = ihdr(width, height, 8, 2);
        byte[] palette = {(byte) 0xff, 0, 0};
        byte[] raw = new byte[height * (1 + width * 3)];
        for (int y = 0; y < height; y++) {
            int row = y * (1 + width * 3);
            raw[row] = 0;
            for (int x = 0; x < width; x++) {
                int i = row + 1 + x * 3;
                raw[i] = 0x11;
                raw[i + 1] = 0x22;
                raw[i + 2] = 0x33;
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

    private static byte[] indexedPixel() {
        byte[] ihdr = ihdr(1, 1, 8, 3);
        byte[] raw = {0, 0};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        chunk(out, "IHDR", ihdr);
        chunk(out, "PLTE", new byte[] {(byte) 0xff, 0, 0});
        chunk(out, "IDAT", deflate(raw));
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] ihdr(int width, int height, int depth, int color) {
        byte[] ihdr = new byte[13];
        put32(ihdr, 0, width);
        put32(ihdr, 4, height);
        ihdr[8] = (byte) depth;
        ihdr[9] = (byte) color;
        return ihdr;
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
        put32(out, data.length);
        out.writeBytes(name);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        put32(out, (int) crc.getValue());
    }

    private static void put32(byte[] b, int i, int v) {
        b[i] = (byte) (v >>> 24);
        b[i + 1] = (byte) (v >>> 16);
        b[i + 2] = (byte) (v >>> 8);
        b[i + 3] = (byte) v;
    }

    private static void put32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }
}
