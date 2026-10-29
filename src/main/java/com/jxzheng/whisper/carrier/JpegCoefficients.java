package com.jxzheng.whisper.carrier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Baseline JPEG at the quantized-coefficient level. The entropy scan is the
 * only regenerated part. Progressive, arithmetic, and 12-bit files are rejected.
 */
public final class JpegCoefficients {

    public int width;
    public int height;
    public int restartInterval;

    private final List<Comp> comps = new ArrayList<>();
    private final List<int[]> planes = new ArrayList<>();
    private final int[][] quant = new int[4][64];
    private final boolean[] quantOk = new boolean[4];
    private final JpegEntropy.Huff[] dc = new JpegEntropy.Huff[4];
    private final JpegEntropy.Huff[] ac = new JpegEntropy.Huff[4];
    private final List<Segment> prefix = new ArrayList<>();
    private final List<Segment> suffix = new ArrayList<>();
    private final List<ScanComp> scan = new ArrayList<>();
    private Segment sos;

    private JpegCoefficients() {
    }

    public static JpegCoefficients parse(byte[] file) {
        if (file == null || file.length < 4 || (file[0] & 0xff) != 0xff || (file[1] & 0xff) != 0xd8) {
            throw new IllegalArgumentException("jpeg: not a JPEG");
        }
        JpegCoefficients jpeg = new JpegCoefficients();
        int i = 2;
        boolean sawSos = false;
        while (i < file.length) {
            if ((file[i] & 0xff) != 0xff) {
                throw new IllegalArgumentException("jpeg: expected a marker");
            }
            int start = i;
            while (i < file.length && (file[i] & 0xff) == 0xff) {
                i++;
            }
            if (i >= file.length) {
                throw new IllegalArgumentException("jpeg: truncated marker");
            }
            int marker = file[i++] & 0xff;
            if (marker == 0xD8) {
                throw new IllegalArgumentException("jpeg: unexpected SOI");
            }
            if (marker == 0xD9) {
                if (!sawSos) {
                    throw new IllegalArgumentException("jpeg: EOI before the scan");
                }
                return jpeg;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                throw new IllegalArgumentException("jpeg: unexpected standalone marker outside the scan");
            }
            if (marker == 0xC2) {
                throw new IllegalArgumentException(
                        "jpeg: progressive JPEG is not supported; only baseline Huffman scans can be updated in place");
            }
            if (unsupported(marker)) {
                throw new IllegalArgumentException(
                        "jpeg: frame marker is not baseline DCT (progressive, arithmetic, and 12-bit JPEG are not supported)");
            }
            if (marker == 0xCC) {
                throw new IllegalArgumentException("jpeg: arithmetic coding is not supported");
            }
            Segment seg = readSegment(file, start, i);
            i = seg.next;
            if (marker == 0xDA) {
                if (sawSos) {
                    throw new IllegalArgumentException("jpeg: multiple scans are not supported");
                }
                jpeg.parseSos(seg.payload);
                Entropy entropy = readEntropy(file, i);
                jpeg.sos = seg;
                jpeg.layout();
                jpeg.decodeScan(entropy.data);
                sawSos = true;
                i = entropy.next;
                continue;
            }
            jpeg.dispatch(marker, seg.payload);
            (sawSos ? jpeg.suffix : jpeg.prefix).add(seg);
        }
        throw new IllegalArgumentException("jpeg: missing EOI");
    }

    public int[] luma() {
        if (comps.isEmpty()) {
            throw new IllegalArgumentException("jpeg: no components");
        }
        Comp c = comps.get(0);
        if (!quantOk[c.q]) {
            throw new IllegalArgumentException("jpeg: missing quantization table");
        }
        return planes.get(0);
    }

    public int[] lumaQuant() {
        return quant[comps.get(0).q].clone();
    }

    public int blocksX() {
        return comps.get(0).blocksX;
    }

    public int blocksY() {
        return comps.get(0).blocksY;
    }

    public void setLuma(int[] coeffs) {
        int[] plane = planes.get(0);
        if (coeffs.length != plane.length) {
            throw new IllegalArgumentException("jpeg: luma plane length mismatch");
        }
        System.arraycopy(coeffs, 0, plane, 0, coeffs.length);
    }

    public int components() {
        return comps.size();
    }

    public int[] plane(int index) {
        return planes.get(index);
    }

    public int nzac() {
        if (planes.isEmpty()) {
            return 0;
        }
        int[] plane = planes.get(0);
        int n = 0;
        for (int b = 0; b < plane.length / 64; b++) {
            int base = b * 64;
            for (int k = 1; k < 64; k++) {
                if (plane[base + k] != 0) {
                    n++;
                }
            }
        }
        return n;
    }

    public byte[] encode(boolean keepMeta) {
        try {
            return encodeTables(keepMeta);
        } catch (JpegEntropy.MissingSymbol missing) {
            // A ±1 change can need a (run, size) the cover's Huffman table never defined.
            installCountedTables();
            return encodeTables(keepMeta);
        }
    }

    private byte[] encodeTables(boolean keepMeta) {
        byte[] entropy = encodeScan();
        int size = 2 + entropy.length + 2;
        for (Segment seg : prefix) {
            if (keepMeta || seg.marker != 0xE1) {
                size += seg.raw.length;
            }
        }
        size += sos.raw.length;
        for (Segment seg : suffix) {
            if (keepMeta || seg.marker != 0xE1) {
                size += seg.raw.length;
            }
        }
        byte[] out = new byte[size];
        int p = 0;
        out[p++] = (byte) 0xff;
        out[p++] = (byte) 0xd8;
        p = append(out, p, prefix, keepMeta);
        System.arraycopy(sos.raw, 0, out, p, sos.raw.length);
        p += sos.raw.length;
        System.arraycopy(entropy, 0, out, p, entropy.length);
        p += entropy.length;
        p = append(out, p, suffix, keepMeta);
        out[p++] = (byte) 0xff;
        out[p++] = (byte) 0xd9;
        return out;
    }

    private static int append(byte[] out, int p, List<Segment> segs, boolean keepMeta) {
        for (Segment seg : segs) {
            if (!keepMeta && seg.marker == 0xE1) {
                continue;
            }
            System.arraycopy(seg.raw, 0, out, p, seg.raw.length);
            p += seg.raw.length;
        }
        return p;
    }

    private void dispatch(int marker, byte[] payload) {
        switch (marker) {
            case 0xC0 -> parseSof(payload);
            case 0xDB -> parseDqt(payload);
            case 0xC4 -> parseDht(payload);
            case 0xDD -> {
                if (payload.length != 2) {
                    throw new IllegalArgumentException("jpeg: bad DRI");
                }
                restartInterval = ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
            }
            default -> {
            }
        }
    }

    private void parseSof(byte[] p) {
        if (p.length < 6 || (p[0] & 0xff) != 8) {
            throw new IllegalArgumentException("jpeg: precision is not 8-bit baseline");
        }
        height = ((p[1] & 0xff) << 8) | (p[2] & 0xff);
        width = ((p[3] & 0xff) << 8) | (p[4] & 0xff);
        int n = p[5] & 0xff;
        if (n != 1 && n != 3) {
            throw new IllegalArgumentException("jpeg: only greyscale or YCbCr is supported");
        }
        if (p.length < 6 + 3 * n || width <= 0 || height <= 0 || width > 20_000 || height > 20_000) {
            throw new IllegalArgumentException("jpeg: bad SOF");
        }
        for (int i = 0; i < n; i++) {
            int off = 6 + 3 * i;
            int h = (p[off + 1] & 0xff) >> 4;
            int v = p[off + 1] & 0x0f;
            if (h < 1 || h > 4 || v < 1 || v > 4) {
                throw new IllegalArgumentException("jpeg: bad sampling factor");
            }
            Comp c = new Comp();
            c.id = p[off] & 0xff;
            c.h = h;
            c.v = v;
            c.q = p[off + 2] & 0xff;
            comps.add(c);
        }
    }

    private void parseDqt(byte[] p) {
        int i = 0;
        while (i < p.length) {
            int id = p[i] & 0x0f;
            int prec = (p[i] & 0xff) >> 4;
            i++;
            if (prec != 0 || id > 3 || i + 64 > p.length) {
                throw new IllegalArgumentException("jpeg: bad DQT");
            }
            for (int k = 0; k < 64; k++) {
                quant[id][JpegEntropy.ZIGZAG[k]] = p[i + k] & 0xff;
            }
            i += 64;
            quantOk[id] = true;
        }
    }

    private void parseDht(byte[] p) {
        int i = 0;
        while (i < p.length) {
            int clazz = (p[i] & 0xff) >> 4;
            int id = p[i] & 0x0f;
            i++;
            if ((clazz != 0 && clazz != 1) || id > 3 || i + 16 > p.length) {
                throw new IllegalArgumentException("jpeg: bad DHT");
            }
            int[] counts = new int[16];
            int sum = 0;
            for (int k = 0; k < 16; k++) {
                counts[k] = p[i + k] & 0xff;
                sum += counts[k];
            }
            i += 16;
            if (i + sum > p.length) {
                throw new IllegalArgumentException("jpeg: bad DHT");
            }
            byte[] symbols = Arrays.copyOfRange(p, i, i + sum);
            i += sum;
            JpegEntropy.Huff huff = JpegEntropy.build(counts, symbols);
            if (clazz == 0) {
                dc[id] = huff;
            } else {
                ac[id] = huff;
            }
        }
    }

    private void parseSos(byte[] p) {
        if (p.length < 1 || comps.isEmpty()) {
            throw new IllegalArgumentException("jpeg: bad SOS");
        }
        int n = p[0] & 0xff;
        if (n != comps.size() || p.length < 1 + 2 * n + 3) {
            throw new IllegalArgumentException("jpeg: non-interleaved or partial scans are not supported");
        }
        if ((p[1 + 2 * n] & 0xff) != 0 || (p[2 + 2 * n] & 0xff) != 63 || (p[3 + 2 * n] & 0xff) != 0) {
            throw new IllegalArgumentException("jpeg: spectral selection is not supported");
        }
        for (int i = 0; i < n; i++) {
            int id = p[1 + 2 * i] & 0xff;
            int td = (p[2 + 2 * i] & 0xff) >> 4;
            int ta = p[2 + 2 * i] & 0x0f;
            int idx = -1;
            for (int c = 0; c < comps.size(); c++) {
                if (comps.get(c).id == id) {
                    idx = c;
                    break;
                }
            }
            if (idx < 0 || td > 3 || ta > 3) {
                throw new IllegalArgumentException("jpeg: SOS references an unknown table");
            }
            ScanComp sc = new ScanComp();
            sc.index = idx;
            sc.dc = td;
            sc.ac = ta;
            scan.add(sc);
        }
    }

    private void layout() {
        int hmax = 1;
        int vmax = 1;
        for (Comp c : comps) {
            hmax = Math.max(hmax, c.h);
            vmax = Math.max(vmax, c.v);
        }
        int mcusX = (width + 8 * hmax - 1) / (8 * hmax);
        int mcusY = (height + 8 * vmax - 1) / (8 * vmax);
        if (mcusX <= 0 || mcusY <= 0) {
            throw new IllegalArgumentException("jpeg: empty frame");
        }
        for (Comp c : comps) {
            c.blocksX = mcusX * c.h;
            c.blocksY = mcusY * c.v;
            int n = c.blocksX * c.blocksY * 64;
            if (n <= 0 || n > 64_000_000) {
                throw new IllegalArgumentException("jpeg: component is unreasonably large");
            }
            planes.add(new int[n]);
        }
    }

    private void decodeScan(byte[] entropy) {
        JpegEntropy.BitReader in = new JpegEntropy.BitReader(entropy);
        int[] hv = maxSample();
        int mcusX = (width + 8 * hv[0] - 1) / (8 * hv[0]);
        int mcusY = (height + 8 * hv[1] - 1) / (8 * hv[1]);
        int[] prev = new int[comps.size()];
        int mcu = 0;
        int rst = 0;
        for (int my = 0; my < mcusY; my++) {
            for (int mx = 0; mx < mcusX; mx++) {
                if (restartInterval > 0 && mcu > 0 && mcu % restartInterval == 0) {
                    in.expectRst(rst);
                    rst = (rst + 1) & 7;
                    Arrays.fill(prev, 0);
                }
                decodeMcu(in, mx, my, prev);
                mcu++;
            }
        }
    }

    private void decodeMcu(JpegEntropy.BitReader in, int mx, int my, int[] prev) {
        for (ScanComp sc : scan) {
            Comp comp = comps.get(sc.index);
            JpegEntropy.Huff dcTable = dc[sc.dc];
            JpegEntropy.Huff acTable = ac[sc.ac];
            if (dcTable == null || acTable == null) {
                throw new IllegalArgumentException("jpeg: missing Huffman table");
            }
            int[] plane = planes.get(sc.index);
            for (int vy = 0; vy < comp.v; vy++) {
                for (int vx = 0; vx < comp.h; vx++) {
                    int bx = mx * comp.h + vx;
                    int by = my * comp.v + vy;
                    int at = (by * comp.blocksX + bx) * 64;
                    prev[sc.index] = JpegEntropy.decodeBlockInto(in, dcTable, acTable, prev[sc.index], plane, at);
                }
            }
        }
    }

    private void installCountedTables() {
        java.util.Map<Integer, int[]> dcFreq = new java.util.HashMap<>();
        java.util.Map<Integer, int[]> acFreq = new java.util.HashMap<>();
        for (ScanComp sc : scan) {
            dcFreq.putIfAbsent(sc.dc, new int[256]);
            acFreq.putIfAbsent(sc.ac, new int[256]);
        }
        int[] hv = maxSample();
        int mcusX = (width + 8 * hv[0] - 1) / (8 * hv[0]);
        int mcusY = (height + 8 * hv[1] - 1) / (8 * hv[1]);
        int[] prev = new int[comps.size()];
        int mcu = 0;
        for (int my = 0; my < mcusY; my++) {
            for (int mx = 0; mx < mcusX; mx++) {
                if (restartInterval > 0 && mcu > 0 && mcu % restartInterval == 0) {
                    java.util.Arrays.fill(prev, 0);
                }
                for (ScanComp sc : scan) {
                    Comp comp = comps.get(sc.index);
                    int[] plane = planes.get(sc.index);
                    int[] dcUse = dcFreq.get(sc.dc);
                    int[] acUse = acFreq.get(sc.ac);
                    for (int vy = 0; vy < comp.v; vy++) {
                        for (int vx = 0; vx < comp.h; vx++) {
                            int at = ((my * comp.v + vy) * comp.blocksX + (mx * comp.h + vx)) * 64;
                            prev[sc.index] = noteBlock(plane, at, prev[sc.index], dcUse, acUse);
                        }
                    }
                }
                mcu++;
            }
        }
        for (var entry : dcFreq.entrySet()) {
            int[] freq = entry.getValue();
            if (freq[0] == 0) {
                freq[0] = 1;
            }
            dc[entry.getKey()] = JpegEntropy.fromFrequencies(freq);
        }
        for (var entry : acFreq.entrySet()) {
            int[] freq = entry.getValue();
            freq[0x00] = Math.max(freq[0x00], 1);
            freq[0xF0] = Math.max(freq[0xF0], 1);
            ac[entry.getKey()] = JpegEntropy.fromFrequencies(freq);
        }
        prefix.removeIf(seg -> seg.marker == 0xC4);
        prefix.add(dhtSegment(dcFreq.keySet(), acFreq.keySet()));
    }

    private static int noteBlock(int[] block, int at, int prev, int[] dcFreq, int[] acFreq) {
        int diff = block[at] - prev;
        int category = JpegEntropy.amplitude(diff)[0];
        dcFreq[category]++;
        int run = 0;
        for (int k = 1; k < 64; k++) {
            int v = block[at + JpegEntropy.ZIGZAG[k]];
            if (v == 0) {
                run++;
                continue;
            }
            while (run > 15) {
                acFreq[0xF0]++;
                run -= 16;
            }
            int size = JpegEntropy.amplitude(v)[0];
            acFreq[(run << 4) | size]++;
            run = 0;
        }
        if (run > 0) {
            acFreq[0x00]++;
        }
        return block[at];
    }

    private Segment dhtSegment(java.util.Set<Integer> dcIds, java.util.Set<Integer> acIds) {
        java.util.List<byte[]> tables = new java.util.ArrayList<>();
        int bytes = 0;
        for (int id : dcIds) {
            byte[] table = huffBytes(0, id, dc[id]);
            tables.add(table);
            bytes += table.length;
        }
        for (int id : acIds) {
            byte[] table = huffBytes(1, id, ac[id]);
            tables.add(table);
            bytes += table.length;
        }
        byte[] payload = new byte[bytes];
        int at = 0;
        for (byte[] table : tables) {
            System.arraycopy(table, 0, payload, at, table.length);
            at += table.length;
        }
        int len = payload.length + 2;
        byte[] raw = new byte[4 + payload.length];
        raw[0] = (byte) 0xff;
        raw[1] = (byte) 0xc4;
        raw[2] = (byte) (len >> 8);
        raw[3] = (byte) len;
        System.arraycopy(payload, 0, raw, 4, payload.length);
        Segment seg = new Segment();
        seg.marker = 0xC4;
        seg.raw = raw;
        seg.payload = payload;
        return seg;
    }

    private static byte[] huffBytes(int clazz, int id, JpegEntropy.Huff huff) {
        int[] counts = new int[16];
        int symbols = 0;
        for (int sym = 0; sym < 256; sym++) {
            if (huff.ok[sym]) {
                counts[huff.nbit[sym] - 1]++;
                symbols++;
            }
        }
        byte[] out = new byte[1 + 16 + symbols];
        out[0] = (byte) ((clazz << 4) | id);
        for (int i = 0; i < 16; i++) {
            out[1 + i] = (byte) counts[i];
        }
        int at = 17;
        for (int length = 1; length <= 16; length++) {
            for (int sym = 0; sym < 256; sym++) {
                if (huff.ok[sym] && huff.nbit[sym] == length) {
                    out[at++] = (byte) sym;
                }
            }
        }
        return out;
    }

    private byte[] encodeScan() {
        JpegEntropy.BitWriter out = new JpegEntropy.BitWriter();
        int[] hv = maxSample();
        int mcusX = (width + 8 * hv[0] - 1) / (8 * hv[0]);
        int mcusY = (height + 8 * hv[1] - 1) / (8 * hv[1]);
        int[] prev = new int[comps.size()];
        int mcu = 0;
        int rst = 0;
        for (int my = 0; my < mcusY; my++) {
            for (int mx = 0; mx < mcusX; mx++) {
                if (restartInterval > 0 && mcu > 0 && mcu % restartInterval == 0) {
                    out.restart(rst);
                    rst = (rst + 1) & 7;
                    Arrays.fill(prev, 0);
                }
                encodeMcu(out, mx, my, prev);
                mcu++;
            }
        }
        out.flush();
        return out.toByteArray();
    }

    private void encodeMcu(JpegEntropy.BitWriter out, int mx, int my, int[] prev) {
        for (ScanComp sc : scan) {
            Comp comp = comps.get(sc.index);
            JpegEntropy.Huff dcTable = dc[sc.dc];
            JpegEntropy.Huff acTable = ac[sc.ac];
            int[] plane = planes.get(sc.index);
            for (int vy = 0; vy < comp.v; vy++) {
                for (int vx = 0; vx < comp.h; vx++) {
                    int bx = mx * comp.h + vx;
                    int by = my * comp.v + vy;
                    int at = (by * comp.blocksX + bx) * 64;
                    prev[sc.index] = JpegEntropy.encodeBlock(out, dcTable, acTable, plane, at, prev[sc.index]);
                }
            }
        }
    }

    private int[] maxSample() {
        int h = 1;
        int v = 1;
        for (Comp c : comps) {
            h = Math.max(h, c.h);
            v = Math.max(v, c.v);
        }
        return new int[] {h, v};
    }

    private static boolean unsupported(int marker) {
        return switch (marker) {
            case 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF -> true;
            default -> false;
        };
    }

    private static Segment readSegment(byte[] file, int start, int i) {
        if (i + 2 > file.length) {
            throw new IllegalArgumentException("jpeg: truncated segment");
        }
        int n = ((file[i] & 0xff) << 8) | (file[i + 1] & 0xff);
        if (n < 2 || i + n > file.length) {
            throw new IllegalArgumentException("jpeg: truncated segment");
        }
        Segment seg = new Segment();
        seg.marker = file[start + 1] == (byte) 0xff ? file[i - 1] & 0xff : file[i - 1] & 0xff;
        seg.raw = Arrays.copyOfRange(file, start, i + n);
        seg.payload = Arrays.copyOfRange(file, i + 2, i + n);
        seg.next = i + n;
        return seg;
    }

    private static Entropy readEntropy(byte[] file, int i) {
        int start = i;
        while (i < file.length) {
            if ((file[i] & 0xff) != 0xff) {
                i++;
                continue;
            }
            int j = i + 1;
            while (j < file.length && (file[j] & 0xff) == 0xff) {
                j++;
            }
            if (j >= file.length) {
                throw new IllegalArgumentException("jpeg: truncated entropy");
            }
            int m = file[j] & 0xff;
            if (m == 0x00 || (m >= 0xD0 && m <= 0xD7)) {
                i = j + 1;
                continue;
            }
            Entropy entropy = new Entropy();
            entropy.data = Arrays.copyOfRange(file, start, i);
            entropy.next = i;
            return entropy;
        }
        throw new IllegalArgumentException("jpeg: scan has no following marker");
    }

    private static final class Comp {
        int id;
        int h;
        int v;
        int q;
        int blocksX;
        int blocksY;
    }

    private static final class ScanComp {
        int index;
        int dc;
        int ac;
    }

    private static final class Segment {
        int marker;
        byte[] raw;
        byte[] payload;
        int next;
    }

    private static final class Entropy {
        byte[] data;
        int next;
    }
}
