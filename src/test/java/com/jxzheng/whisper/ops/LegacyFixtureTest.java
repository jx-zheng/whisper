package com.jxzheng.whisper.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Images embedded by Whisper 0.9.0 ({@code 565ebd3}) before the v2 envelope.
 */
class LegacyFixtureTest {

    @Test
    void plainZhangTangFixtureExtracts() throws Exception {
        var image = StegoOperations.readImage(fixture("fixtures/legacy/zhang-tang-plain.png"));
        byte[] message = new StegoOperations().extract(image, "legacy-stego-key", false, null, "zhang-tang", "aes");
        assertEquals("legacy-plain-v0.9", new String(message, StandardCharsets.UTF_8));
    }

    @Test
    void encryptedZhangTangFixtureExtracts() throws Exception {
        var image = StegoOperations.readImage(fixture("fixtures/legacy/zhang-tang-aes.png"));
        byte[] message = new StegoOperations().extract(
                image, "legacy-stego-key", true, "legacy-enc-key", "zhang-tang", "aes");
        assertEquals("legacy-encrypted-v0.9", new String(message, StandardCharsets.UTF_8));
    }

    private static Path fixture(String name) throws Exception {
        var url = LegacyFixtureTest.class.getResource("/" + name);
        if (url == null) {
            throw new IllegalStateException("missing " + name);
        }
        return Path.of(url.toURI());
    }
}
