package com.jxzheng.whisper.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;

class CoverConverterGreyTest {

    @Test
    void sixteenBitGreyKeepsHighByte() throws Exception {
        byte[] png = greyPng(1, 16, new byte[] {0, (byte) 0x80, 0x00});
        byte[] eight = CoverConverter.toEightBitPng(png);
        RasterImage image = PngCodec.decode(eight);
        assertEquals(0, image.colorType);
        assertEquals(0x80, image.samples[0] & 0xff);
    }

    @Test
    void oneBitGreyScalesToFullRange() throws Exception {
        byte[] png = greyPng(8, 1, new byte[] {0, (byte) 0x80});
        byte[] eight = CoverConverter.toEightBitPng(png);
        RasterImage image = PngCodec.decode(eight);
        assertEquals(0, image.colorType);
        assertEquals(255, image.samples[0] & 0xff);
        assertEquals(0, image.samples[1] & 0xff);
    }

    /** One row, eight pixels. {@code samples} includes the filter byte. */
    private static byte[] greyPng(int width, int bitDepth, byte[] filteredRow) {
        byte[] ihdr = new byte[13];
        ihdr[0] = (byte) (width >>> 24);
        ihdr[1] = (byte) (width >>> 16);
        ihdr[2] = (byte) (width >>> 8);
        ihdr[3] = (byte) width;
        ihdr[7] = 1;
        ihdr[8] = (byte) bitDepth;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        chunk(out, "IHDR", ihdr);
        chunk(out, "IDAT", deflate(filteredRow));
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        byte[] buf = new byte[64];
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

    private static void put32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }
}
