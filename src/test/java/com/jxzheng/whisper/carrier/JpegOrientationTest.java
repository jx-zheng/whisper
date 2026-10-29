package com.jxzheng.whisper.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JpegOrientationTest {

    @Test
    void orientationOtherThanOneWarns() {
        byte[] jpeg = jpegWithOrientation(6);
        assertEquals(6, JpegCoefficients.exifOrientation(jpeg));
        assertTrue(JpegCoefficients.orientationWarning(jpeg).contains("rotated"));
        assertNull(JpegCoefficients.orientationWarning(jpegWithOrientation(1)));
    }

    private static byte[] jpegWithOrientation(int orientation) {
        byte[] payload = new byte[] {
                'E', 'x', 'i', 'f', 0, 0,
                'I', 'I', 42, 0, 8, 0, 0, 0,
                1, 0,
                0x12, 0x01, 3, 0, 1, 0, 0, 0,
                (byte) orientation, 0, 0, 0
        };
        byte[] out = new byte[2 + 4 + payload.length + 2];
        out[0] = (byte) 0xff;
        out[1] = (byte) 0xd8;
        out[2] = (byte) 0xff;
        out[3] = (byte) 0xe1;
        out[4] = (byte) ((payload.length + 2) >> 8);
        out[5] = (byte) (payload.length + 2);
        System.arraycopy(payload, 0, out, 6, payload.length);
        out[out.length - 2] = (byte) 0xff;
        out[out.length - 1] = (byte) 0xd9;
        return out;
    }
}
