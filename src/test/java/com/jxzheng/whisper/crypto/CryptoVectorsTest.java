package com.jxzheng.whisper.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class CryptoVectorsTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void argon2idMatchesRfc9106() {
        byte[] password = filled(32, 0x01);
        byte[] salt = filled(16, 0x02);
        byte[] secret = filled(8, 0x03);
        byte[] ad = filled(12, 0x04);
        byte[] tag = Argon2idKdf.deriveBytes(password, salt, secret, ad, 3, 32, 4);
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", HEX.formatHex(tag));
    }

    @Test
    void hkdfMatchesRfc5869A1() {
        byte[] ikm = filled(22, 0x0b);
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");
        byte[] okm = Hkdf.expand(ikm, salt, info, 42);
        assertEquals(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                HEX.formatHex(okm));
    }

    @Test
    void chacha20Poly1305MatchesRfc8439() throws Exception {
        byte[] key = HEX.parseHex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
        byte[] nonce = HEX.parseHex("070000004041424344454647");
        byte[] aad = HEX.parseHex("50515253c0c1c2c3c4c5c6c7");
        byte[] pt = HEX.parseHex(
                "4c616469657320616e642047656e746c656d656e206f662074686520636c617373206f66202739393a"
                        + "204966204920636f756c64206f6666657220796f75206f6e6c79206f6e652074697020666f72207468"
                        + "65206675747572652c2073756e73637265656e20776f756c642062652069742e");
        byte[] out = new byte[pt.length + 16];
        int n = ChaCha20Poly1305Suite.INSTANCE.seal(key, nonce, aad, pt, 0, pt.length, out, 0);
        assertEquals(pt.length + 16, n);
        assertEquals(
                "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282"
                        + "fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324"
                        + "e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902"
                        + "ecbd0600691",
                HEX.formatHex(out));
        byte[] back = new byte[pt.length];
        int m = ChaCha20Poly1305Suite.INSTANCE.open(key, nonce, aad, out, 0, out.length, back, 0);
        assertEquals(pt.length, m);
        assertArrayEquals(pt, back);
    }

    @Test
    void aes256GcmMatchesNistCase() throws Exception {
        byte[] key = HEX.parseHex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308");
        byte[] nonce = HEX.parseHex("cafebabefacedbaddecaf888");
        byte[] pt = HEX.parseHex(
                "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532f"
                        + "cf0e2449a6b525b16aedf5aa0de657ba637b391aafd255");
        byte[] aad = HEX.parseHex("feedfacedeadbeeffeedfacedeadbeefabaddad2");
        byte[] out = new byte[pt.length + 16];
        AesGcmSuite.INSTANCE.seal(key, nonce, aad, pt, 0, pt.length, out, 0);
        assertEquals(
                "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7"
                        + "b08b1056828838c5f61e6393ba7a0abcc9f662898015ad2df7cd675b4f09163b41ebf980a7f638",
                HEX.formatHex(out));
    }

    @Test
    void streamLengthsAndChunkTampering() throws Exception {
        byte[] key = filled(32, 0x11);
        assertRoundTrip(key, 0);
        assertRoundTrip(key, Stream.CHUNK);
        assertRoundTrip(key, Stream.CHUNK + 1);
        assertRoundTrip(key, Stream.CHUNK * 2 + 20);
        byte[] pt = new byte[Stream.CHUNK * 2 + 20];
        Arrays.fill(pt, (byte) 9);
        byte[] ct = new byte[Stream.cipherLen(pt.length)];
        Stream.seal(ChaCha20Poly1305Suite.INSTANCE, key, new byte[] {1, 2, 3}, pt, 0, pt.length, ct, 0);
        byte[] swapped = ct.clone();
        int chunk = Stream.CHUNK + Stream.TAG;
        System.arraycopy(ct, chunk, swapped, 0, chunk);
        System.arraycopy(ct, 0, swapped, chunk, chunk);
        assertThrows(AuthException.class, () -> Stream.open(ChaCha20Poly1305Suite.INSTANCE, key, new byte[] {1, 2, 3}, swapped));
        assertThrows(AuthException.class,
                () -> Stream.open(ChaCha20Poly1305Suite.INSTANCE, key, new byte[] {1, 2, 3}, Arrays.copyOf(ct, ct.length - 1)));
        byte[] extra = Arrays.copyOf(ct, ct.length + chunk);
        System.arraycopy(ct, 0, extra, ct.length, chunk);
        assertThrows(AuthException.class, () -> Stream.open(ChaCha20Poly1305Suite.INSTANCE, key, new byte[] {1, 2, 3}, extra));
        byte[] truncated = Arrays.copyOf(ct, chunk);
        assertThrows(AuthException.class,
                () -> Stream.open(ChaCha20Poly1305Suite.INSTANCE, key, new byte[] {1, 2, 3}, truncated));
    }

    @Test
    void keyFileRoundTripAndEveryBitFlipFails() throws Exception {
        byte[] key = filled(32, 0x42);
        byte[] frame = new byte[200];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (byte) (i * 3 + 1);
        }
        Envelope.SealRequest seal = new Envelope.SealRequest();
        seal.keyFile = key.clone();
        seal.suite = Envelope.SuiteKind.CHACHA;
        seal.compress = Compression.Mode.OFF;
        Envelope.Sealed sealed = Envelope.seal(seal, frame);
        Envelope.OpenRequest open = new Envelope.OpenRequest();
        open.keyFile = key.clone();
        assertArrayEquals(frame, Envelope.open(open, sealed.bytes()).frame());
        for (int bit = 0; bit < sealed.bytes().length * 8; bit++) {
            byte[] flipped = sealed.bytes().clone();
            flipped[bit >>> 3] ^= (byte) (1 << (bit & 7));
            Envelope.OpenRequest attempt = new Envelope.OpenRequest();
            attempt.keyFile = key.clone();
            AuthException error = assertThrows(AuthException.class, () -> Envelope.open(attempt, flipped));
            assertTrue(error.kind() == AuthException.Kind.FORMAT
                    || error.kind() == AuthException.Kind.AUTH
                    || error.kind() == AuthException.Kind.INCOMPLETE
                    || error.kind() == AuthException.Kind.DECOMPRESS
                    || error.kind() == AuthException.Kind.SALT);
        }
    }

    @Test
    void aesAndIntegrityRoundTrip() throws Exception {
        byte[] key = filled(32, 0x7);
        byte[] frame = "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (Envelope.SuiteKind suite : new Envelope.SuiteKind[] {Envelope.SuiteKind.AES, Envelope.SuiteKind.INTEGRITY}) {
            Envelope.SealRequest seal = new Envelope.SealRequest();
            seal.keyFile = key.clone();
            seal.suite = suite;
            seal.compress = Compression.Mode.OFF;
            Envelope.Sealed sealed = Envelope.seal(seal, frame);
            Envelope.OpenRequest open = new Envelope.OpenRequest();
            open.keyFile = key.clone();
            Envelope.Opened opened = Envelope.open(open, sealed.bytes());
            assertArrayEquals(frame, opened.frame());
            assertEquals(suite, opened.suite());
        }
    }

    @Test
    void compressionRoundTrip() throws Exception {
        byte[] frame = new byte[400];
        Arrays.fill(frame, (byte) 'A');
        Envelope.SealRequest seal = new Envelope.SealRequest();
        seal.keyFile = filled(32, 3);
        seal.compress = Compression.Mode.AUTO;
        Envelope.Sealed sealed = Envelope.seal(seal, frame);
        assertTrue(sealed.compressed());
        assertTrue(sealed.bytes().length < frame.length);
        Envelope.OpenRequest open = new Envelope.OpenRequest();
        open.keyFile = filled(32, 3);
        assertArrayEquals(frame, Envelope.open(open, sealed.bytes()).frame());
    }

    @Test
    void singleAndSplitKeysRoundTripAtTestProfile() throws Exception {
        byte[] frame = "split-or-single".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Envelope.SealRequest single = new Envelope.SealRequest();
        single.profile = KdfProfile.TEST;
        single.stegoPass = "one-pass".toCharArray();
        single.suite = Envelope.SuiteKind.CHACHA;
        single.compress = Compression.Mode.OFF;
        Envelope.Sealed sealedSingle = Envelope.seal(single, frame);
        Envelope.OpenRequest openSingle = new Envelope.OpenRequest();
        openSingle.profile = KdfProfile.TEST;
        openSingle.stegoPass = "one-pass".toCharArray();
        assertArrayEquals(frame, Envelope.open(openSingle, sealedSingle.bytes()).frame());
        Envelope.OpenRequest wrongSingle = new Envelope.OpenRequest();
        wrongSingle.profile = KdfProfile.TEST;
        wrongSingle.stegoPass = "other-pass".toCharArray();
        assertEquals(AuthException.Kind.AUTH,
                assertThrows(AuthException.class, () -> Envelope.open(wrongSingle, sealedSingle.bytes())).kind());

        Envelope.SealRequest split = new Envelope.SealRequest();
        split.profile = KdfProfile.TEST;
        split.split = true;
        split.stegoPass = "walk".toCharArray();
        split.encPass = "cipher".toCharArray();
        split.compress = Compression.Mode.OFF;
        Envelope.Sealed sealedSplit = Envelope.seal(split, frame);
        Envelope.OpenRequest badEnc = new Envelope.OpenRequest();
        badEnc.profile = KdfProfile.TEST;
        badEnc.split = true;
        badEnc.stegoPass = "walk".toCharArray();
        badEnc.encPass = "nope".toCharArray();
        AuthException auth = assertThrows(AuthException.class, () -> Envelope.open(badEnc, sealedSplit.bytes()));
        assertEquals(AuthException.Kind.AUTH, auth.kind());
        Envelope.OpenRequest good = new Envelope.OpenRequest();
        good.profile = KdfProfile.TEST;
        good.split = true;
        good.stegoPass = "walk".toCharArray();
        good.encPass = "cipher".toCharArray();
        assertArrayEquals(frame, Envelope.open(good, sealedSplit.bytes()).frame());
    }

    @Test
    void wipedKeysAreZero() throws Exception {
        Secrets.trackWipes = true;
        Secrets.wiped.clear();
        try {
            Envelope.SealRequest seal = new Envelope.SealRequest();
            seal.keyFile = filled(32, 9);
            seal.compress = Compression.Mode.OFF;
            Envelope.Sealed sealed = Envelope.seal(seal, new byte[] {1, 2, 3, 4});
            assertFalse(Secrets.allZero(sealed.visitSeed()));
            Secrets.wipe(sealed.visitSeed());
            Envelope.OpenRequest open = new Envelope.OpenRequest();
            open.keyFile = filled(32, 9);
            Envelope.open(open, sealed.bytes());
            assertFalse(Secrets.wiped.isEmpty());
            for (byte[] secret : Secrets.wiped) {
                assertTrue(Secrets.allZero(secret));
            }
        } finally {
            Secrets.trackWipes = false;
            Secrets.wiped.clear();
        }
    }

    @Test
    void legacyDecryptsAesCipherOutput() throws Exception {
        var legacy = new LegacyAes128GcmPbkdf2();
        byte[] ct = new com.jxzheng.whisper.encryption.AesCipher().encrypt(new byte[] {9, 8, 7}, "secret");
        assertArrayEquals(new byte[] {9, 8, 7}, legacy.decrypt(ct, "secret".toCharArray()));
    }

    private static void assertRoundTrip(byte[] key, int n) throws Exception {
        byte[] pt = new byte[n];
        for (int i = 0; i < n; i++) {
            pt[i] = (byte) i;
        }
        byte[] aad = new byte[] {4, 5};
        byte[] ct = new byte[Stream.cipherLen(n)];
        Stream.seal(ChaCha20Poly1305Suite.INSTANCE, key, aad, pt, 0, n, ct, 0);
        assertArrayEquals(pt, Stream.open(ChaCha20Poly1305Suite.INSTANCE, key, aad, ct));
    }

    private static byte[] filled(int n, int value) {
        byte[] out = new byte[n];
        Arrays.fill(out, (byte) value);
        return out;
    }
}
