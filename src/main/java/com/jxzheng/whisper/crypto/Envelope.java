package com.jxzheng.whisper.crypto;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Whisper v2 envelope. There is no plaintext magic. The 16-byte salt is both
 * inside the header and, for adaptive schemes, spread on the cover.
 */
public final class Envelope {

    public static final int HEADER = 42;
    public static final int MAX_FRAME = 64 * 1024 * 1024;
    public static final int VERSION = 2;

    private Envelope() {
    }

    public enum SuiteKind {
        CHACHA(ChaCha20Poly1305Suite.INSTANCE),
        AES(AesGcmSuite.INSTANCE);

        final AeadSuite suite;

        SuiteKind(AeadSuite suite) {
            this.suite = suite;
        }

        public AeadSuite suite() {
            return suite;
        }
    }

    public static final class SealRequest {
        public char[] stegoPass;
        public char[] encPass;
        public boolean split;
        public KdfProfile profile = KdfProfile.DEFAULT;
        public SuiteKind suite = SuiteKind.CHACHA;
        public Compression.Mode compress = Compression.Mode.AUTO;
        /** Set when the content magic or entropy says compression will not help. */
        public boolean contentAlreadyDense;
        public byte[] salt;
        public byte[] fileNonce;
        public SecureRandom random = new SecureRandom();
    }

    public record Sealed(byte[] bytes, byte[] salt, byte[] visitSeed, boolean compressed) {
    }

    public static final class OpenRequest {
        public char[] stegoPass;
        public char[] encPass;
        public boolean split;
        /** Profile to try. High is never guessed. */
        public KdfProfile profile = KdfProfile.DEFAULT;
        /** Public salt read off the cover. Null when the scheme has none. */
        public byte[] expectedSalt;
        /** Already-derived stego master, so reveal can reuse {@link KeyCache}. Null to derive. */
        public byte[] stegoMaster;
    }

    public record Opened(byte[] frame, SuiteKind suite, boolean compressed, KdfProfile profile) {
    }

    public static Sealed seal(SealRequest request, byte[] frame) throws AuthException {
        if (frame == null) {
            throw new IllegalArgumentException("frame is missing");
        }
        if (frame.length > MAX_FRAME) {
            throw new AuthException(AuthException.Kind.FORMAT, "frame exceeds 64 MiB");
        }
        byte[] salt = request.salt != null ? request.salt.clone() : random(request.random, 16);
        byte[] fileNonce = request.fileNonce != null ? request.fileNonce.clone() : random(request.random, 16);
        byte[] body = frame;
        boolean compressed = false;
        KeySchedule.Derived derived = null;
        try {
            derived = new KeySchedule().derive(
                    Secrets.copy(request.stegoPass),
                    Secrets.copy(request.encPass),
                    request.split,
                    request.profile,
                    salt,
                    fileNonce);
            if (shouldCompress(request, frame)) {
                byte[] deflated = Compression.deflate(frame);
                if (request.compress == Compression.Mode.ON || deflated.length < frame.length) {
                    body = deflated;
                    compressed = true;
                } else {
                    Secrets.wipe(deflated);
                }
            }
            int flags = VERSION;
            if (compressed) {
                flags |= 0x10;
            }
            flags |= (request.suite.suite.suiteBits() & 3) << 5;
            byte[] header = header(flags, request.profile, salt, fileNonce, frame.length);
            int ctLen = Stream.cipherLen(body.length);
            byte[] envelope = new byte[HEADER + ctLen];
            System.arraycopy(header, 0, envelope, 0, HEADER);
            Stream.seal(request.suite.suite, derived.payloadKey(), header, body, 0, body.length, envelope, HEADER);
            byte[] visit = derived.visitSeed().clone();
            return new Sealed(envelope, salt, visit, compressed);
        } finally {
            if (compressed && body != frame) {
                Secrets.wipe(body);
            }
            if (derived != null) {
                derived.close();
            }
            Secrets.wipe(request.stegoPass);
            Secrets.wipe(request.encPass);
            Secrets.wipe(fileNonce);
        }
    }

    public static Opened open(OpenRequest request, byte[] blob) throws AuthException {
        Parsed parsed = parse(blob);
        if (request.expectedSalt != null && !MessageDigest.isEqual(request.expectedSalt, parsed.salt)) {
            Secrets.wipe(request.stegoPass);
            Secrets.wipe(request.encPass);
            throw new AuthException(AuthException.Kind.SALT, parsed.suite.suite.suiteBits(), "salt mismatch");
        }
        KdfProfile profile = request.profile;
        if (parsed.profile != profile) {
            Secrets.wipe(request.stegoPass);
            Secrets.wipe(request.encPass);
            throw new AuthException(AuthException.Kind.AUTH, parsed.suite.suite.suiteBits(), "kdf profile mismatch");
        }
        byte[] master = request.stegoMaster;
        boolean ownedMaster = false;
        KeySchedule.Derived derived = null;
        try {
            if (master == null) {
                master = Argon2idKdf.INSTANCE.derive(
                        request.stegoPass == null ? new char[0] : request.stegoPass, parsed.salt, profile);
                ownedMaster = true;
            }
            derived = reopen(master, request, profile, parsed);
            byte[] frame = decrypt(parsed, derived, blob);
            return new Opened(frame, parsed.suite, parsed.compressed, parsed.profile);
        } finally {
            if (derived != null) {
                derived.close();
            }
            if (ownedMaster) {
                Secrets.wipe(master);
            }
            Secrets.wipe(request.stegoPass);
            Secrets.wipe(request.encPass);
        }
    }

    private static KeySchedule.Derived reopen(byte[] stegoMaster, OpenRequest request, KdfProfile profile, Parsed parsed) {
        byte[] encMaster = null;
        try {
            if (!request.split) {
                encMaster = stegoMaster.clone();
            } else {
                if (request.encPass == null) {
                    throw new IllegalArgumentException("encryption passphrase is required");
                }
                byte[] encSalt = KeySchedule.encSalt(parsed.salt);
                try {
                    encMaster = Argon2idKdf.INSTANCE.derive(request.encPass, encSalt, profile);
                } finally {
                    Secrets.wipe(encSalt);
                }
            }
            byte[] visit = Hkdf.expand(stegoMaster, null, KeySchedule.INFO_STEGO, 32);
            byte[] aead = Hkdf.expand(encMaster, null, KeySchedule.INFO_AEAD, 32);
            byte[] payload = Hkdf.expand(aead, parsed.fileNonce, KeySchedule.INFO_PAYLOAD, 32);
            byte[] mac = Hkdf.expand(encMaster, parsed.fileNonce, KeySchedule.INFO_MAC, 32);
            Secrets.wipe(aead);
            return new KeySchedule.Derived(visit, payload, mac, stegoMaster.clone());
        } finally {
            Secrets.wipe(encMaster);
        }
    }

    private static byte[] decrypt(Parsed parsed, KeySchedule.Derived derived, byte[] blob) throws AuthException {
        byte[] ct = Arrays.copyOfRange(blob, HEADER, blob.length);
        byte[] body;
        try {
            body = Stream.open(parsed.suite.suite, derived.payloadKey(), Arrays.copyOf(blob, HEADER), ct);
        } finally {
            Secrets.wipe(ct);
        }
        try {
            if (!parsed.compressed) {
                if (body.length != parsed.frameLen) {
                    throw new AuthException(AuthException.Kind.AUTH, parsed.suite.suite.suiteBits(), "length mismatch");
                }
                byte[] frame = body;
                body = null;
                return frame;
            }
            if (parsed.frameLen > MAX_FRAME) {
                throw new AuthException(AuthException.Kind.FORMAT, "frame exceeds 64 MiB");
            }
            return Compression.inflate(body, (int) parsed.frameLen);
        } finally {
            Secrets.wipe(body);
        }
    }

    static Parsed parse(byte[] blob) throws AuthException {
        if (blob == null || blob.length < HEADER + 16) {
            throw new AuthException(AuthException.Kind.FORMAT, "envelope is too short");
        }
        int flags = blob[0] & 0xff;
        if ((flags & 0x80) != 0 || (flags & 0x0f) != VERSION || ((flags >> 5) & 3) == 3) {
            throw new AuthException(AuthException.Kind.FORMAT, "bad flags");
        }
        SuiteKind suite = switch ((flags >> 5) & 3) {
            case 0 -> SuiteKind.CHACHA;
            case 1 -> SuiteKind.AES;
            default -> throw new AuthException(AuthException.Kind.FORMAT, "bad suite");
        };
        KdfProfile profile = KdfProfile.fromWire(blob[1] & 0xff);
        if (profile == null) {
            throw new AuthException(AuthException.Kind.FORMAT, suite.suite.suiteBits(), "unknown kdf profile");
        }
        long frameLen = 0;
        for (int i = 0; i < 8; i++) {
            frameLen = (frameLen << 8) | (blob[34 + i] & 0xffL);
        }
        if (frameLen > MAX_FRAME) {
            throw new AuthException(AuthException.Kind.FORMAT, suite.suite.suiteBits(), "frame exceeds 64 MiB");
        }
        if (blob.length < HEADER + 16) {
            throw new AuthException(AuthException.Kind.INCOMPLETE, suite.suite.suiteBits(), "missing tag");
        }
        Parsed parsed = new Parsed();
        parsed.suite = suite;
        parsed.profile = profile;
        parsed.compressed = (flags & 0x10) != 0;
        parsed.frameLen = frameLen;
        parsed.salt = Arrays.copyOfRange(blob, 2, 18);
        parsed.fileNonce = Arrays.copyOfRange(blob, 18, 34);
        return parsed;
    }

    private static boolean shouldCompress(SealRequest request, byte[] frame) {
        if (frame.length == 0 || request.compress == Compression.Mode.OFF) {
            return false;
        }
        if (request.compress == Compression.Mode.ON) {
            return true;
        }
        return frame.length >= 64 && !request.contentAlreadyDense && Compression.shannon(frame) < 7.2;
    }

    private static byte[] header(int flags, KdfProfile profile, byte[] salt, byte[] fileNonce, int frameLen) {
        byte[] header = new byte[HEADER];
        header[0] = (byte) flags;
        header[1] = (byte) profile.wire();
        System.arraycopy(salt, 0, header, 2, 16);
        System.arraycopy(fileNonce, 0, header, 18, 16);
        long n = frameLen;
        for (int i = 7; i >= 0; i--) {
            header[34 + i] = (byte) n;
            n >>>= 8;
        }
        return header;
    }

    private static byte[] random(SecureRandom random, int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    static final class Parsed {
        SuiteKind suite;
        KdfProfile profile;
        boolean compressed;
        long frameLen;
        byte[] salt;
        byte[] fileNonce;
    }
}
