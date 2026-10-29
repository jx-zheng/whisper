package com.jxzheng.whisper.carrier;

import java.util.Arrays;

/** Huffman tables and the JPEG bit pipes. Package-private to the coefficient codec. */
final class JpegEntropy {

    static final int[] ZIGZAG = {
            0, 1, 8, 16, 9, 2, 3, 10,
            17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34,
            27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36,
            29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46,
            53, 60, 61, 54, 47, 55, 62, 63
    };

    static final class Huff {
        final int[] code = new int[256];
        final int[] nbit = new int[256];
        final boolean[] ok = new boolean[256];
        final int[][] dec = new int[17][];
        final int[][] decSym = new int[17][];

        int decode(BitReader in) {
            int code = 0;
            for (int n = 1; n <= 16; n++) {
                code = (code << 1) | in.readBit();
                int[] table = dec[n];
                if (table != null) {
                    int lo = 0;
                    int hi = table.length - 1;
                    while (lo <= hi) {
                        int mid = (lo + hi) >>> 1;
                        int v = table[mid];
                        if (v == code) {
                            return decSym[n][mid];
                        }
                        if (v < code) {
                            lo = mid + 1;
                        } else {
                            hi = mid - 1;
                        }
                    }
                }
            }
            throw new IllegalArgumentException("jpeg: invalid Huffman code");
        }

        void write(BitWriter out, int symbol) {
            if (!ok[symbol]) {
                throw new MissingSymbol(symbol);
            }
            out.write(code[symbol], nbit[symbol]);
        }
    }

    /** A (run, size) or DC category appeared that the cover's Huffman table does not contain. */
    static final class MissingSymbol extends IllegalArgumentException {
        MissingSymbol(int symbol) {
            super("jpeg: Huffman table has no symbol 0x" + Integer.toHexString(symbol));
        }
    }

    /**
     * JPEG Annex K length-limited Huffman table, matching libjpeg
     * {@code jpeg_gen_optimal_table}. Symbol 256 is a reserved pseudo-symbol
     * so no real code is all ones. Codes longer than 16 bits are shortened
     * in place instead of rejected.
     */
    static Huff fromFrequencies(int[] freqIn) {
        long[] freq = new long[257];
        boolean any = false;
        for (int sym = 0; sym < 256; sym++) {
            if (freqIn != null && freqIn[sym] > 0) {
                freq[sym] = freqIn[sym];
                any = true;
            }
        }
        if (!any) {
            freq[0] = 1;
        }
        freq[256] = 1;
        int[] codesize = new int[257];
        int[] others = new int[257];
        java.util.Arrays.fill(others, -1);
        for (;;) {
            int c1 = -1;
            long best = 1_000_000_000L;
            for (int i = 0; i <= 256; i++) {
                if (freq[i] != 0 && freq[i] <= best) {
                    best = freq[i];
                    c1 = i;
                }
            }
            int c2 = -1;
            best = 1_000_000_000L;
            for (int i = 0; i <= 256; i++) {
                if (freq[i] != 0 && freq[i] <= best && i != c1) {
                    best = freq[i];
                    c2 = i;
                }
            }
            if (c2 < 0) {
                break;
            }
            freq[c1] += freq[c2];
            freq[c2] = 0;
            codesize[c1]++;
            while (others[c1] >= 0) {
                c1 = others[c1];
                codesize[c1]++;
            }
            others[c1] = c2;
            codesize[c2]++;
            while (others[c2] >= 0) {
                c2 = others[c2];
                codesize[c2]++;
            }
        }
        int[] bits = new int[33];
        for (int i = 0; i <= 256; i++) {
            if (codesize[i] == 0) {
                continue;
            }
            if (codesize[i] > 32) {
                throw new IllegalArgumentException("jpeg: Huffman code longer than 32 bits");
            }
            bits[codesize[i]]++;
        }
        for (int i = 32; i > 16; i--) {
            while (bits[i] > 0) {
                int j = i - 2;
                while (j > 0 && bits[j] == 0) {
                    j--;
                }
                if (j <= 0) {
                    throw new IllegalArgumentException("jpeg: cannot limit Huffman code length");
                }
                bits[i] -= 2;
                bits[i - 1]++;
                bits[j + 1] += 2;
                bits[j]--;
            }
        }
        int longest = 32;
        while (longest > 0 && bits[longest] == 0) {
            longest--;
        }
        if (longest > 0) {
            bits[longest]--;
        }
        int[] counts = new int[16];
        int nsym = 0;
        for (int len = 1; len <= 16; len++) {
            counts[len - 1] = Math.max(bits[len], 0);
            nsym += counts[len - 1];
        }
        byte[] symbols = new byte[nsym];
        int at = 0;
        for (int len = 1; len <= 32; len++) {
            for (int sym = 0; sym <= 255; sym++) {
                if (codesize[sym] == len) {
                    if (at >= symbols.length) {
                        throw new IllegalArgumentException("jpeg: Huffman symbol count mismatch");
                    }
                    symbols[at++] = (byte) sym;
                }
            }
        }
        if (at != symbols.length) {
            throw new IllegalArgumentException("jpeg: Huffman symbol count mismatch");
        }
        return build(counts, symbols);
    }

    static Huff build(int[] counts, byte[] symbols) {
        int sum = 0;
        for (int count : counts) {
            sum += count;
        }
        if (sum != symbols.length) {
            throw new IllegalArgumentException("jpeg: Huffman table length mismatch");
        }
        Huff huff = new Huff();
        int code = 0;
        int k = 0;
        for (int length = 1; length <= 16; length++) {
            int n = counts[length - 1];
            if (n > 0) {
                huff.dec[length] = new int[n];
                huff.decSym[length] = new int[n];
            }
            for (int j = 0; j < n; j++) {
                int sym = symbols[k] & 0xff;
                if (huff.ok[sym]) {
                    throw new IllegalArgumentException("jpeg: duplicate Huffman symbol");
                }
                huff.code[sym] = code;
                huff.nbit[sym] = length;
                huff.ok[sym] = true;
                huff.dec[length][j] = code;
                huff.decSym[length][j] = sym;
                k++;
                code++;
            }
            code <<= 1;
        }
        return huff;
    }

    static int extend(int value, int bits) {
        if (bits <= 0) {
            return 0;
        }
        int vt = 1 << (bits - 1);
        if (value < vt) {
            value += (-1 << bits) + 1;
        }
        return value;
    }

    static int[] amplitude(int value) {
        if (value == 0) {
            return new int[] {0, 0};
        }
        int a = Math.abs(value);
        int n = 0;
        for (int x = a; x > 0; x >>>= 1) {
            n++;
        }
        int bits = value < 0 ? value + (1 << n) - 1 : value;
        return new int[] {n, bits};
    }

    static int decodeBlockInto(BitReader in, Huff dc, Huff ac, int prev, int[] dest, int destAt) {
        int sym = dc.decode(in);
        if (sym > 11) {
            throw new IllegalArgumentException("jpeg: DC category out of range");
        }
        int diff = sym > 0 ? extend(in.readBits(sym), sym) : 0;
        int val = prev + diff;
        Arrays.fill(dest, destAt, destAt + 64, 0);
        dest[destAt] = val;
        int k = 1;
        while (k < 64) {
            sym = ac.decode(in);
            if (sym == 0x00) {
                break;
            }
            if (sym == 0xF0) {
                k += 16;
                if (k > 64) {
                    throw new IllegalArgumentException("jpeg: AC run overflow");
                }
                continue;
            }
            int run = sym >> 4;
            int size = sym & 0x0f;
            if (size == 0 || size > 10) {
                throw new IllegalArgumentException("jpeg: bad AC size");
            }
            k += run;
            if (k >= 64) {
                throw new IllegalArgumentException("jpeg: AC run overflow");
            }
            dest[destAt + ZIGZAG[k]] = extend(in.readBits(size), size);
            k++;
        }
        return val;
    }

    static int encodeBlock(BitWriter out, Huff dc, Huff ac, int[] block, int at, int prev) {
        writeAmp(out, dc, block[at] - prev);
        int run = 0;
        for (int k = 1; k < 64; k++) {
            int v = block[at + ZIGZAG[k]];
            if (v == 0) {
                run++;
                continue;
            }
            while (run > 15) {
                ac.write(out, 0xF0);
                run -= 16;
            }
            int[] amp = amplitude(v);
            if (amp[0] < 1 || amp[0] > 10) {
                throw new IllegalArgumentException("jpeg: AC coefficient does not fit a baseline Huffman symbol");
            }
            ac.write(out, (run << 4) | amp[0]);
            out.write(amp[1], amp[0]);
            run = 0;
        }
        if (run > 0) {
            ac.write(out, 0x00);
        }
        return block[at];
    }

    private static void writeAmp(BitWriter out, Huff huff, int value) {
        int[] amp = amplitude(value);
        if (amp[0] > 11) {
            throw new IllegalArgumentException("jpeg: DC difference does not fit a baseline code");
        }
        huff.write(out, amp[0]);
        if (amp[0] > 0) {
            out.write(amp[1], amp[0]);
        }
    }

    static final class BitReader {
        private final byte[] data;
        private int i;
        private int acc;
        private int nbit;

        BitReader(byte[] data) {
            this.data = data;
        }

        int readBit() {
            if (nbit == 0) {
                acc = nextByte() & 0xff;
                nbit = 8;
            }
            nbit--;
            return (acc >> nbit) & 1;
        }

        int readBits(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | readBit();
            }
            return v;
        }

        void expectRst(int n) {
            nbit = 0;
            if (i >= data.length || data[i] != (byte) 0xff) {
                throw new IllegalArgumentException("jpeg: expected a restart marker");
            }
            while (i < data.length && data[i] == (byte) 0xff) {
                i++;
            }
            if (i >= data.length) {
                throw new IllegalArgumentException("jpeg: truncated restart");
            }
            int m = data[i++] & 0xff;
            if (m != (0xD0 + (n & 7))) {
                throw new IllegalArgumentException("jpeg: expected RST" + (n & 7));
            }
        }

        private int nextByte() {
            if (i >= data.length) {
                throw new IllegalArgumentException("jpeg: truncated entropy");
            }
            int c = data[i++] & 0xff;
            if (c != 0xff) {
                return c;
            }
            while (i < data.length && data[i] == (byte) 0xff) {
                i++;
            }
            if (i >= data.length) {
                throw new IllegalArgumentException("jpeg: truncated entropy");
            }
            int n = data[i++] & 0xff;
            if (n == 0x00) {
                return 0xff;
            }
            throw new IllegalArgumentException("jpeg: unexpected marker in entropy data");
        }
    }

    static final class BitWriter {
        byte[] buf = new byte[256];
        int len;
        private int acc;
        private int n;

        void write(int value, int bits) {
            if (bits == 0) {
                return;
            }
            acc = (acc << bits) | (value & ((1 << bits) - 1));
            n += bits;
            while (n >= 8) {
                n -= 8;
                emit((acc >> n) & 0xff);
                if (n == 0) {
                    acc = 0;
                } else {
                    acc &= (1 << n) - 1;
                }
            }
        }

        void flush() {
            if (n == 0) {
                return;
            }
            int pad = 8 - n;
            acc = (acc << pad) | ((1 << pad) - 1);
            n = 0;
            emit(acc & 0xff);
            acc = 0;
        }

        void restart(int n) {
            flush();
            append(0xff);
            append(0xD0 | (n & 7));
        }

        private void emit(int c) {
            append(c);
            if (c == 0xff) {
                append(0x00);
            }
        }

        private void append(int c) {
            if (len == buf.length) {
                buf = Arrays.copyOf(buf, buf.length * 2);
            }
            buf[len++] = (byte) c;
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buf, len);
        }
    }
}
