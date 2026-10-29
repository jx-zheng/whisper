package com.jxzheng.whisper.ops;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.jxzheng.whisper.carrier.Carrier;
import com.jxzheng.whisper.carrier.RasterImage;
import com.jxzheng.whisper.schemes.EmbedOptions;
import com.jxzheng.whisper.schemes.RatePreset;
import com.jxzheng.whisper.schemes.adaptive.HillStcScheme;

class GifCoverTest {

    @Test
    void gifCoverConvertsAndEmbeds() throws Exception {
        BufferedImage drawn = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < drawn.getHeight(); y++) {
            for (int x = 0; x < drawn.getWidth(); x++) {
                drawn.setRGB(x, y, new Color(20 + (x % 200), 40, 60).getRGB());
            }
        }
        ByteArrayOutputStream gif = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(drawn, "gif", gif));
        byte[] bytes = gif.toByteArray();
        assertEquals('G', bytes[0]);
        assertEquals("png", PayloadOps.sniff(bytes));

        RasterImage raster = PayloadOps.coverRaster(bytes);
        assertEquals(160, raster.width);
        assertEquals(120, raster.height);
        assertTrue(raster.colorType == 2 || raster.colorType == 6);
        assertTrue((raster.samples[0] & 0xff) > 0);

        byte[] payload = new byte[] {1, 2, 3, 4};
        byte[] seed = new byte[32];
        seed[0] = 9;
        HillStcScheme scheme = new HillStcScheme();
        EmbedOptions options = new EmbedOptions(RatePreset.STANDARD, false, "png");
        var stego = scheme.embed(Carrier.raster(raster, bytes, "png"), payload, seed, options);
        assertArrayEquals(payload, scheme.extract(stego, seed, options));
    }
}
