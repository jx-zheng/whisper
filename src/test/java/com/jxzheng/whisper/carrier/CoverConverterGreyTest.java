package com.jxzheng.whisper.carrier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void greyTransparencyBecomesAlphaAndColorimetryIsKept() throws Exception {
        byte[] gama = {0, 0, (byte) 0xb1, (byte) 0x8f};
        byte[] srgb = {0};
        byte[] iccp = iccpChunk();
        byte[] trns = {0, 0};
        byte[] png = greyPng(2, 8, new byte[] {0, 0, (byte) 0xff}, gama, srgb, iccp, trns);
        byte[] eight = CoverConverter.toEightBitPng(png);
        RasterImage image = PngCodec.decode(eight);
        assertEquals(4, image.colorType);
        assertEquals(0, image.samples[0] & 0xff);
        assertEquals(0, image.samples[1] & 0xff);
        assertEquals(255, image.samples[2] & 0xff);
        assertEquals(255, image.samples[3] & 0xff);
        assertArrayEquals(gama, chunk(image, "gAMA"));
        assertArrayEquals(srgb, chunk(image, "sRGB"));
        assertArrayEquals(iccp, chunk(image, "iCCP"));
        assertNull(chunk(image, "tRNS"));
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

    /** One row. {@code filteredRow} includes the filter byte. Extra chunks sit before IDAT. */
    private static byte[] greyPng(int width, int bitDepth, byte[] filteredRow, byte[]... extras) {
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
        if (extras.length > 0) {
            chunk(out, "gAMA", extras[0]);
        }
        if (extras.length > 1) {
            chunk(out, "sRGB", extras[1]);
        }
        if (extras.length > 2) {
            chunk(out, "iCCP", extras[2]);
        }
        if (extras.length > 3) {
            chunk(out, "tRNS", extras[3]);
        }
        chunk(out, "IDAT", deflate(filteredRow));
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] chunk(RasterImage image, String type) {
        for (RasterImage.Chunk chunk : image.ancillary) {
            if (type.equals(chunk.type())) {
                return chunk.data();
            }
        }
        return null;
    }

    private static byte[] iccpChunk() {
        byte[] name = "test\u0000".getBytes(StandardCharsets.US_ASCII);
        byte[] body = deflate(new byte[] {1, 2, 3, 4});
        byte[] out = new byte[name.length + 1 + body.length];
        System.arraycopy(name, 0, out, 0, name.length);
        out[name.length] = 0;
        System.arraycopy(body, 0, out, name.length + 1, body.length);
        return out;
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
