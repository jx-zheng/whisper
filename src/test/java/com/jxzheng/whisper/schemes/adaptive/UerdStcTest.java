package com.jxzheng.whisper.schemes.adaptive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.jxzheng.whisper.carrier.Carrier;
import com.jxzheng.whisper.carrier.JpegCoefficients;
import com.jxzheng.whisper.schemes.EmbedOptions;
import com.jxzheng.whisper.schemes.RatePreset;
import com.jxzheng.whisper.schemes.costs.UerdCost;
import com.jxzheng.whisper.support.Json;

class UerdStcTest {

    @Test
    void jpegRoundTripKeepsCoefficientsAndDecodes() throws Exception {
        for (String name : List.of("fixtures/jpeg/noise.jpg", "fixtures/jpeg/photo-qf75.jpg")) {
            byte[] file = resource(name);
            JpegCoefficients jpeg = JpegCoefficients.parse(file);
            byte[] encoded = jpeg.encode(true);
            assertTrue(ImageIO.read(new ByteArrayInputStream(encoded)) != null, name);
            JpegCoefficients again = JpegCoefficients.parse(encoded);
            assertArrayEquals(jpeg.luma(), again.luma(), name);
            assertArrayEquals(jpeg.lumaQuant(), again.lumaQuant(), name);
            for (int c = 1; c < jpeg.components(); c++) {
                assertArrayEquals(jpeg.plane(c), again.plane(c), name + " plane " + c);
            }
        }
    }

    @Test
    void uerdMatchesGoOnNoise() throws Exception {
        Map<String, Object> json = Json.object(Json.parse(resourceText("golden/uerd.json")));
        JpegCoefficients jpeg = JpegCoefficients.parse(resource("fixtures/jpeg/noise.jpg"));
        assertEquals(Json.integer(json.get("blocksX")), jpeg.blocksX());
        assertEquals(Json.integer(json.get("nzac")), jpeg.nzac());
        double[] costs = UerdCost.compute(jpeg.luma(), jpeg.blocksX(), jpeg.blocksY(), jpeg.lumaQuant());
        assertClose(Json.array(json.get("costHead")), costs, 0);
        assertClose(Json.array(json.get("costTail")), costs, costs.length - Json.array(json.get("costTail")).size());
    }

    @Test
    void fiftyPayloadsRoundTripAndOnlySafeCoefficientsChange() throws Exception {
        Random random = new Random(11);
        UerdStcScheme scheme = new UerdStcScheme();
        EmbedOptions options = new EmbedOptions("", RatePreset.STANDARD, true, "jpg");
        byte[] seed = new byte[32];
        seed[2] = 9;
        for (String name : List.of("fixtures/jpeg/noise.jpg", "fixtures/jpeg/photo-qf75.jpg")) {
            byte[] file = resource(name);
            JpegCoefficients before = JpegCoefficients.parse(file);
            for (int n = 0; n < 50; n++) {
                byte[] payload = new byte[1 + random.nextInt(12)];
                random.nextBytes(payload);
                Carrier stego = scheme.embed(Carrier.encoded(file, "jpg"), payload, seed, options);
                assertArrayEquals(payload, scheme.extract(stego, seed, options));
                assertTrue(ImageIO.read(new ByteArrayInputStream(stego.encoded())) != null);
                if (n == 0) {
                    JpegCoefficients after = JpegCoefficients.parse(stego.encoded());
                    assertCoefficientRules(before, after);
                }
            }
        }
    }

    private static void assertCoefficientRules(JpegCoefficients before, JpegCoefficients after) {
        for (int c = 1; c < before.components(); c++) {
            assertArrayEquals(before.plane(c), after.plane(c));
        }
        int[] a = before.luma();
        int[] b = after.luma();
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            if (i % 64 == 0 || Math.abs(a[i]) <= 1) {
                assertEquals(a[i], b[i], "index " + i);
                continue;
            }
            int delta = b[i] - a[i];
            assertTrue(delta == 0 || delta == 1 || delta == -1, "delta " + delta + " at " + i);
            if (delta != 0) {
                assertTrue(Math.abs(b[i]) >= 1);
                assertTrue(a[i] != 0 && b[i] != 0);
            }
        }
    }

    private static void assertClose(List<Object> expected, double[] actual, int offset) {
        for (int i = 0; i < expected.size(); i++) {
            Object value = expected.get(i);
            if (value == null) {
                assertTrue(Double.isInfinite(actual[offset + i]));
            } else {
                double want = Json.number(value);
                assertEquals(want, actual[offset + i], Math.max(1.0, Math.abs(want)) * 1e-6);
            }
        }
    }

    private static byte[] resource(String name) throws Exception {
        try (var in = UerdStcTest.class.getResourceAsStream("/" + name)) {
            return in.readAllBytes();
        }
    }

    private static String resourceText(String name) throws Exception {
        return new String(resource(name), StandardCharsets.UTF_8);
    }
}
