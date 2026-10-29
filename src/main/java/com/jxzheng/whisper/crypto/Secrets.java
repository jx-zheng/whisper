package com.jxzheng.whisper.crypto;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Best-effort wiping of secret material. Java cannot guarantee the copies are gone.
 */
public final class Secrets {

    /** When true, {@link #wipe(byte[])} records the array so tests can see the zeros. */
    static boolean trackWipes;
    static final List<byte[]> wiped = new ArrayList<>();

    private Secrets() {
    }

    public static void wipe(byte[] secret) {
        if (secret == null) {
            return;
        }
        Arrays.fill(secret, (byte) 0);
        if (trackWipes) {
            wiped.add(secret);
        }
    }

    public static void wipe(char[] secret) {
        if (secret != null) {
            Arrays.fill(secret, '\0');
        }
    }

    public static byte[] copy(byte[] secret) {
        return secret == null ? null : secret.clone();
    }

    public static char[] copy(char[] secret) {
        return secret == null ? null : secret.clone();
    }

    /** UTF-8 of {@code pass}. The caller wipes the returned array. */
    public static byte[] utf8(char[] pass) {
        if (pass == null || pass.length == 0) {
            return new byte[0];
        }
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer out = ByteBuffer.allocate(pass.length * 3 + 16);
        encoder.encode(CharBuffer.wrap(pass), out, true);
        encoder.flush(out);
        byte[] exact = new byte[out.position()];
        System.arraycopy(out.array(), 0, exact, 0, exact.length);
        wipe(out.array());
        return exact;
    }

    static boolean allZero(byte[] secret) {
        if (secret == null) {
            return true;
        }
        for (byte b : secret) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
