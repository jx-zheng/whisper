package com.jxzheng.whisper.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class KeyedPrngTest {

    @Test
    void samePassphraseReplaysSameSequence() {
        KeyedPrng a = KeyedPrng.fromPassphrase("demo-key");
        KeyedPrng b = KeyedPrng.fromPassphrase("demo-key");
        assertArrayEquals(sample(a, 32), sample(b, 32));
    }

    @Test
    void differentPassphrasesDiverge() {
        assertFalse(Arrays.equals(
                sample(KeyedPrng.fromPassphrase("alpha"), 16),
                sample(KeyedPrng.fromPassphrase("bravo"), 16)));
    }

    @Test
    void hashCodeCollidingStringsStillDiverge() {
        // Java String.hashCode collisions: "Aa" and "BB" both hash to 2112.
        assertEquals("Aa".hashCode(), "BB".hashCode());
        KeyedPrng a = KeyedPrng.fromPassphrase("Aa");
        KeyedPrng b = KeyedPrng.fromPassphrase("BB");
        assertNotEquals(a.nextLong(), b.nextLong());
    }

    @Test
    void rejectsEmptyPassphrase() {
        assertThrows(IllegalArgumentException.class, () -> KeyedPrng.fromPassphrase(""));
    }

    @Test
    void ignoresSetSeed() {
        KeyedPrng a = KeyedPrng.fromPassphrase("fixed");
        KeyedPrng b = KeyedPrng.fromPassphrase("fixed");
        b.setSeed(12345L);
        assertEquals(a.nextInt(), b.nextInt());
    }

    @Test
    void nextIntRespectsNonPowerOfTwoBounds() {
        KeyedPrng prng = KeyedPrng.fromPassphrase("bounds");
        for (int i = 0; i < 2000; i++) {
            int value = prng.nextInt(1920);
            assertTrue(value >= 0 && value < 1920, "value=" + value);
        }
    }

    @Test
    void goldenSequenceIsStable() {
        // Lock the walk so KDF/domain changes are intentional.
        KeyedPrng prng = KeyedPrng.fromPassphrase("whisper-golden");
        assertEquals(1298854993, prng.nextInt());
        assertEquals(245, prng.nextInt(1920));
        assertEquals(-1336859357, prng.nextInt());
    }

    private static int[] sample(KeyedPrng prng, int n) {
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = prng.nextInt();
        }
        return values;
    }
}
