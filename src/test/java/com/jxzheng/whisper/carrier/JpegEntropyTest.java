package com.jxzheng.whisper.carrier;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JpegEntropyTest {

    @Test
    void lengthLimitedTableHasNoAllOnesCode() {
        int[] freq = new int[256];
        freq[0] = 100_000;
        for (int i = 1; i < 80; i++) {
            freq[i] = 1;
        }
        JpegEntropy.Huff huff = JpegEntropy.fromFrequencies(freq);
        for (int sym = 0; sym < 256; sym++) {
            if (!huff.ok[sym]) {
                continue;
            }
            assertTrue(huff.nbit[sym] >= 1 && huff.nbit[sym] <= 16, "symbol " + sym);
            int allOnes = (1 << huff.nbit[sym]) - 1;
            assertTrue(huff.code[sym] != allOnes, "symbol " + sym + " is all ones");
        }
        assertTrue(huff.ok[0]);
        assertTrue(huff.ok[79]);
    }
}
