package com.jxzheng.whisper.crypto;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Excluded from the default verify. Run with {@code mvn -Pbench test}. */
class CryptoBenchTest {

    @Test
    @Tag("bench")
    void argon2idDefaultProfile() {
        byte[] salt = new byte[16];
        salt[0] = 1;
        long start = System.nanoTime();
        byte[] key = Argon2idKdf.INSTANCE.derive("benchmark".toCharArray(), salt, KdfProfile.DEFAULT);
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        Secrets.wipe(key);
        System.out.println("argon2id default: " + seconds + " s");
        assertTrue(seconds <= 1.5, "Argon2id default took " + seconds + " s");
    }

    @Test
    @Tag("bench")
    void sealOpenOneMiB() throws Exception {
        byte[] key = new byte[32];
        key[0] = 4;
        byte[] pt = new byte[1024 * 1024];
        byte[] ct = new byte[Stream.cipherLen(pt.length)];
        for (AeadSuite suite : new AeadSuite[] {ChaCha20Poly1305Suite.INSTANCE, AesGcmSuite.INSTANCE}) {
            long start = System.nanoTime();
            Stream.seal(suite, key, new byte[] {1}, pt, 0, pt.length, ct, 0);
            byte[] back = Stream.open(suite, key, new byte[] {1}, ct);
            double ms = (System.nanoTime() - start) / 1_000_000.0;
            System.out.println(suite.id() + " 1 MiB seal+open: " + ms + " ms");
            assertTrue(ms <= 50, suite.id() + " took " + ms + " ms");
            assertTrue(back.length == pt.length);
        }
    }
}
