package com.jxzheng.whisper.payload;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import com.jxzheng.whisper.crypto.Secrets;

/**
 * Authenticated plaintext inside the envelope. SHA-256(content) lives here,
 * never outside the AEAD.
 */
public final class PayloadFrame {

    public static final int VERSION = 1;
    public static final int MAX_NAME = 255;
    public static final int MAX_MIME = 100;

    private PayloadFrame() {
    }

    public record Built(byte[] bytes, byte[] sha256, String name, String mime) {
    }

    public record Parsed(
            PayloadKind kind,
            String name,
            String mime,
            byte[] content,
            byte[] sha256,
            boolean reencoded) {
    }

    public static Built build(PayloadKind kind, String name, String mime, byte[] content, boolean reencoded) {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        String safeMime = NameSanitizer.mime(mime);
        String safeName = kind == PayloadKind.TEXT ? "" : NameSanitizer.sanitize(name, safeMime);
        if (kind == PayloadKind.TEXT) {
            safeName = "";
        }
        byte[] nameBytes = safeName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (nameBytes.length > MAX_NAME) {
            safeName = NameSanitizer.sanitize(safeName, safeMime);
            nameBytes = safeName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        byte[] mimeBytes = safeMime.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] digest = sha256(content);
        int flags = 0;
        if (nameBytes.length > 0) {
            flags |= 0x01;
        }
        if (reencoded) {
            flags |= 0x02;
        }
        int size = 3 + 2 + nameBytes.length + 1 + mimeBytes.length + 8 + 32 + content.length;
        byte[] out = new byte[size];
        int p = 0;
        out[p++] = VERSION;
        out[p++] = (byte) kind.wire();
        out[p++] = (byte) flags;
        out[p++] = (byte) (nameBytes.length >>> 8);
        out[p++] = (byte) nameBytes.length;
        System.arraycopy(nameBytes, 0, out, p, nameBytes.length);
        p += nameBytes.length;
        out[p++] = (byte) mimeBytes.length;
        System.arraycopy(mimeBytes, 0, out, p, mimeBytes.length);
        p += mimeBytes.length;
        long n = content.length;
        for (int i = 7; i >= 0; i--) {
            out[p + i] = (byte) n;
            n >>>= 8;
        }
        p += 8;
        System.arraycopy(digest, 0, out, p, 32);
        p += 32;
        System.arraycopy(content, 0, out, p, content.length);
        return new Built(out, digest, safeName, safeMime);
    }

    public static Parsed parse(byte[] frame) throws PayloadFormatException {
        if (frame == null || frame.length < 3 + 2 + 1 + 8 + 32) {
            throw new PayloadFormatException(PayloadFormatException.Kind.FORMAT, "frame too short");
        }
        if ((frame[0] & 0xff) != VERSION) {
            throw new PayloadFormatException(PayloadFormatException.Kind.FORMAT, "bad frame version");
        }
        PayloadKind kind = PayloadKind.fromWire(frame[1] & 0xff);
        if (kind == null) {
            throw new PayloadFormatException(PayloadFormatException.Kind.FORMAT, "bad kind");
        }
        int flags = frame[2] & 0xff;
        if ((flags & ~0x03) != 0) {
            throw new PayloadFormatException(PayloadFormatException.Kind.FORMAT, "bad frame flags");
        }
        int nameLen = ((frame[3] & 0xff) << 8) | (frame[4] & 0xff);
        if (nameLen > MAX_NAME) {
            throw new PayloadFormatException(PayloadFormatException.Kind.FORMAT, "name too long");
        }
        boolean hasName = (flags & 0x01) != 0;
        if (hasName != (nameLen > 0) || (kind == PayloadKind.TEXT && nameLen != 0)) {
            throw new PayloadFormatException(PayloadFormatException.Kind.FORMAT, "name flag mismatch");
        }
        int p = 5;
        if (frame.length < p + nameLen + 1 + 8 + 32) {
            throw new PayloadFormatException(PayloadFormatException.Kind.FORMAT, "truncated name");
        }
        String rawName = new String(frame, p, nameLen, java.nio.charset.StandardCharsets.UTF_8);
        p += nameLen;
        int mimeLen = frame[p++] & 0xff;
        if (mimeLen > MAX_MIME || frame.length < p + mimeLen + 8 + 32) {
            throw new PayloadFormatException(PayloadFormatException.Kind.FORMAT, "bad mime");
        }
        String mime = new String(frame, p, mimeLen, java.nio.charset.StandardCharsets.US_ASCII);
        if (!ascii(mime)) {
            throw new PayloadFormatException(PayloadFormatException.Kind.FORMAT, "mime is not ASCII");
        }
        p += mimeLen;
        long contentLen = 0;
        for (int i = 0; i < 8; i++) {
            contentLen = (contentLen << 8) | (frame[p++] & 0xffL);
        }
        int remaining = frame.length - p - 32;
        if (contentLen != remaining || contentLen > EnvelopeCap()) {
            throw new PayloadFormatException(PayloadFormatException.Kind.FORMAT, "content length mismatch");
        }
        byte[] expected = Arrays.copyOfRange(frame, p, p + 32);
        p += 32;
        byte[] content = Arrays.copyOfRange(frame, p, frame.length);
        byte[] actual = sha256(content);
        if (!MessageDigest.isEqual(expected, actual)) {
            Secrets.wipe(content);
            Secrets.wipe(actual);
            throw new PayloadFormatException(PayloadFormatException.Kind.HASH, "content hash does not match");
        }
        String safeMime = NameSanitizer.mime(mime);
        String safeName = kind == PayloadKind.TEXT ? "" : NameSanitizer.sanitize(rawName, safeMime);
        return new Parsed(kind, safeName, safeMime, content, actual, (flags & 0x02) != 0);
    }

    private static long EnvelopeCap() {
        return com.jxzheng.whisper.crypto.Envelope.MAX_FRAME;
    }

    private static boolean ascii(String mime) {
        for (int i = 0; i < mime.length(); i++) {
            char c = mime.charAt(i);
            if (c < 0x20 || c >= 0x7f) {
                return false;
            }
        }
        return true;
    }

    public static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
