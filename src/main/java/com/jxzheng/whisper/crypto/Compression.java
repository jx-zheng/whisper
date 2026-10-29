package com.jxzheng.whisper.crypto;

import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Raw DEFLATE (level 6). Payloads that are already compressed, or that do not
 * shrink, stay as they are when the mode is {@link Mode#AUTO}.
 */
public final class Compression {

    public enum Mode {
        AUTO, OFF, ON
    }

    private Compression() {
    }

    public static boolean looksCompressed(byte[] data) {
        if (data == null || data.length < 2) {
            return false;
        }
        if (startsWith(data, new byte[] {'R', 'I', 'F', 'F'})) {
            return true;
        }
        byte[][] sigs = {
                {0x50, 0x4b, 0x03, 0x04},
                {0x50, 0x4b, 0x05, 0x06},
                {0x1f, (byte) 0x8b},
                {(byte) 0x89, 'P', 'N', 'G'},
                {(byte) 0xff, (byte) 0xd8, (byte) 0xff},
                {'f', 'L', 'a', 'C'},
                {'%', 'P', 'D', 'F'},
                {(byte) 0xfd, '7', 'z', 'X', 'Z', 0},
                {'B', 'Z', 'h'},
                {'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c},
                {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd},
                {'O', 'g', 'g', 'S'}
        };
        for (byte[] sig : sigs) {
            if (startsWith(data, sig)) {
                return true;
            }
        }
        return data.length >= 12 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p';
    }

    public static double shannon(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }
        int[] counts = new int[256];
        for (byte b : data) {
            counts[b & 0xff]++;
        }
        double n = data.length;
        double h = 0;
        for (int count : counts) {
            if (count == 0) {
                continue;
            }
            double p = count / n;
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }

    public static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(6, true);
        deflater.setInput(input);
        deflater.finish();
        byte[] buf = new byte[input.length + 64 + input.length / 50];
        int off = 0;
        while (!deflater.finished()) {
            if (off == buf.length) {
                byte[] bigger = new byte[buf.length * 2];
                System.arraycopy(buf, 0, bigger, 0, off);
                Secrets.wipe(buf);
                buf = bigger;
            }
            int n = deflater.deflate(buf, off, buf.length - off);
            if (n == 0 && !deflater.finished()) {
                deflater.end();
                Secrets.wipe(buf);
                throw new IllegalStateException("DEFLATE made no progress");
            }
            off += n;
        }
        deflater.end();
        byte[] exact = new byte[off];
        System.arraycopy(buf, 0, exact, 0, off);
        Secrets.wipe(buf);
        return exact;
    }

    public static byte[] inflate(byte[] input, int expectedLen) throws AuthException {
        if (expectedLen < 0 || expectedLen > Envelope.MAX_FRAME) {
            throw new AuthException(AuthException.Kind.DECOMPRESS, "deflated length is over the cap");
        }
        Inflater inflater = new Inflater(true);
        inflater.setInput(input);
        byte[] out = new byte[expectedLen];
        try {
            int n = expectedLen == 0 ? 0 : inflater.inflate(out);
            if (n != expectedLen || !inflater.finished() || inflater.getRemaining() != 0) {
                Secrets.wipe(out);
                throw new AuthException(AuthException.Kind.DECOMPRESS, "decompression failed");
            }
            return out;
        } catch (DataFormatException e) {
            Secrets.wipe(out);
            throw new AuthException(AuthException.Kind.DECOMPRESS, "decompression failed");
        } finally {
            inflater.end();
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
