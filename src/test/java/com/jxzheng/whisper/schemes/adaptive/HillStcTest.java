package com.jxzheng.whisper.schemes.adaptive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.jxzheng.whisper.exceptions.MessageTooLongException;
import com.jxzheng.whisper.carrier.BmpCodec;
import com.jxzheng.whisper.carrier.Carrier;
import com.jxzheng.whisper.carrier.PngCodec;
import com.jxzheng.whisper.carrier.RasterImage;
import com.jxzheng.whisper.schemes.EmbedOptions;
import com.jxzheng.whisper.schemes.RatePreset;
import com.jxzheng.whisper.schemes.costs.HillCost;
import com.jxzheng.whisper.support.Json;

class HillStcTest {

    @Test
    void pngFixturesRoundTripAndIdatIsEightKib() throws Exception {
        for (String name : List.of("flat.png", "greyscale.png", "large.png", "photo.png", "tiny.png")) {
            byte[] file = resource("fixtures/png/" + name);
            RasterImage image = PngCodec.decode(file);
            byte[] encoded = PngCodec.encode(image, false);
            RasterImage again = PngCodec.decode(encoded);
            assertArrayEquals(image.samples, again.samples, name);
            assertEquals(image.width, again.width);
            assertEquals(image.colorType, again.colorType);
        }
        RasterImage noisy = new RasterImage();
        noisy.width = 180;
        noisy.height = 140;
        noisy.colorType = 2;
        noisy.samples = new byte[noisy.width * noisy.height * 3];
        int state = 0x12345678;
        for (int i = 0; i < noisy.samples.length; i++) {
            state = state * 1664525 + 1013904223;
            noisy.samples[i] = (byte) (state >>> 24);
        }
        noisy.ancillary.add(new RasterImage.Chunk("tEXt", "Comment\0secret".getBytes(StandardCharsets.ISO_8859_1)));
        noisy.ancillary.add(new RasterImage.Chunk("gAMA", new byte[] {0, 0, (byte) 0xb1, (byte) 0x8f}));
        noisy.ancillary.add(new RasterImage.Chunk("tRNS", new byte[] {(byte) 0xff, 0, 0}));
        byte[] png = PngCodec.encode(noisy, false);
        String types = chunkTypes(png);
        assertTrue(types.contains("gAMA"));
        assertTrue(types.contains("tRNS"));
        assertFalse(types.contains("tEXt"));
        int[] lens = PngCodec.idatLengths(png);
        assertTrue(lens.length > 1, "compressed size " + Arrays.stream(lens).sum());
        for (int i = 0; i < lens.length - 1; i++) {
            assertEquals(PngCodec.IDAT, lens[i]);
        }
        assertTrue(lens[lens.length - 1] <= PngCodec.IDAT);
        byte[] kept = PngCodec.encode(noisy, true);
        assertTrue(chunkTypes(kept).contains("tEXt"));
    }

    @Test
    void hillMatchesGoOnPhotoCrop() throws Exception {
        Map<String, Object> json = Json.object(Json.parse(resourceText("golden/hill.json")));
        RasterImage photo = PngCodec.decode(resource("fixtures/png/photo.png"));
        byte[] plane = HillStcScheme.plane(photo).pixels();
        List<Object> expectedPixels = Json.array(json.get("pixels"));
        assertEquals(expectedPixels.size(), plane.length);
        for (int i = 0; i < plane.length; i++) {
            assertEquals(Json.integer(expectedPixels.get(i)), plane[i] & 0xff);
        }
        double[] costs = HillCost.compute(plane, photo.width, photo.height);
        List<Object> expected = Json.array(json.get("costs"));
        assertEquals(expected.size(), costs.length);
        for (int i = 0; i < costs.length; i++) {
            double want = Json.number(expected.get(i));
            double scale = Math.max(1.0, Math.abs(want));
            assertEquals(want, costs[i], scale * 1e-6, "cost " + i);
        }
    }

    @Test
    void hillStcRoundTripsPngAndBmp() throws Exception {
        RasterImage photo = PngCodec.decode(resource("fixtures/png/photo.png"));
        byte[] payload = new byte[8];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i + 3);
        }
        byte[] seed = new byte[32];
        seed[1] = 4;
        HillStcScheme scheme = new HillStcScheme();
        EmbedOptions options = new EmbedOptions("", RatePreset.STANDARD, false, "png");
        Carrier stego = scheme.embed(Carrier.raster(photo, null, "png"), payload, seed, options, null);
        assertArrayEquals(payload, scheme.extract(stego, seed, options));
        assertEquals("png", stego.format());
        assertTrue(stego.encoded()[0] == (byte) 0x89);

        RasterImage bmp = BmpCodec.read(resource("fixtures/bmp/sample.bmp"));
        EmbedOptions bmpOut = new EmbedOptions("", RatePreset.STANDARD, false, "bmp");
        Carrier hidden = scheme.embed(Carrier.raster(bmp, null, "bmp"), payload, seed, bmpOut, null);
        assertEquals("bmp", hidden.format());
        assertArrayEquals(payload, scheme.extract(hidden, seed, bmpOut));
        assertEquals('B', hidden.encoded()[0]);
    }

    @Test
    void rejectsEnvelopeAbovePresetBudget() throws Exception {
        RasterImage photo = PngCodec.decode(resource("fixtures/png/photo.png"));
        byte[] payload = new byte[200];
        byte[] seed = new byte[32];
        HillStcScheme scheme = new HillStcScheme();
        EmbedOptions options = new EmbedOptions("", RatePreset.STANDARD, false, "png");
        assertThrows(MessageTooLongException.class,
                () -> scheme.embed(Carrier.raster(photo, null, "png"), payload, seed, options, null));
    }

    @Test
    @Tag("mem")
    void twelveMegapixelEmbedFitsIn512m() {
        int width = 4000;
        int height = 3000;
        RasterImage image = new RasterImage();
        image.width = width;
        image.height = height;
        image.colorType = 0;
        image.samples = new byte[width * height];
        for (int i = 0; i < image.samples.length; i += 64) {
            image.samples[i] = (byte) (i * 17);
        }
        byte[] payload = new byte[64];
        payload[0] = 7;
        byte[] seed = new byte[32];
        seed[0] = 1;
        HillStcScheme scheme = new HillStcScheme();
        EmbedOptions options = new EmbedOptions("", RatePreset.STANDARD, false, "png");
        long start = System.nanoTime();
        Carrier stego = scheme.embed(Carrier.raster(image, null, "png"), payload, seed, options, null);
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        System.out.println("12MP hill-stc hide incl. self-check, excl. KDF: " + seconds + " s (spec target 6 s)");
        assertArrayEquals(payload, scheme.extract(stego, seed, options));
        assertTrue(seconds <= 40.0, "12MP hide took " + seconds + " s");
    }

    private static String chunkTypes(byte[] png) {
        StringBuilder types = new StringBuilder();
        int i = 8;
        while (i + 12 <= png.length) {
            int n = ((png[i] & 0xff) << 24) | ((png[i + 1] & 0xff) << 16) | ((png[i + 2] & 0xff) << 8) | (png[i + 3] & 0xff);
            types.append(new String(png, i + 4, 4, StandardCharsets.US_ASCII)).append(' ');
            i += 12 + n;
            if (types.toString().contains("IEND")) {
                break;
            }
        }
        return types.toString();
    }

    private static byte[] resource(String name) throws Exception {
        try (var in = HillStcTest.class.getResourceAsStream("/" + name)) {
            return in.readAllBytes();
        }
    }

    private static String resourceText(String name) throws Exception {
        return new String(resource(name), StandardCharsets.UTF_8);
    }
}
