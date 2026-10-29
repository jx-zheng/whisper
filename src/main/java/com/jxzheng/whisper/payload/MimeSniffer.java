package com.jxzheng.whisper.payload;

/** Magic-byte types. The extension and the declared MIME are not trusted. */
public final class MimeSniffer {

    private MimeSniffer() {
    }

    public static String mime(byte[] data) {
        if (data == null) {
            return "application/octet-stream";
        }
        if (starts(data, new byte[] {(byte) 0x89, 'P', 'N', 'G'})) {
            return "image/png";
        }
        if (starts(data, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            return "image/jpeg";
        }
        if (starts(data, new byte[] {'G', 'I', 'F', '8'})) {
            return "image/gif";
        }
        if (starts(data, new byte[] {'B', 'M'})) {
            return "image/bmp";
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        if (starts(data, new byte[] {'%', 'P', 'D', 'F'})) {
            return "application/pdf";
        }
        if (starts(data, new byte[] {0x50, 0x4b, 0x03, 0x04})) {
            return "application/zip";
        }
        return "application/octet-stream";
    }

    public static boolean image(byte[] data) {
        String mime = mime(data);
        return mime.startsWith("image/");
    }

    public static boolean printableText(byte[] data) {
        if (data == null || data.length == 0) {
            return true;
        }
        int controls = 0;
        for (byte b : data) {
            int c = b & 0xff;
            if (c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            if (c < 0x20 || c == 0x7f) {
                controls++;
            }
        }
        return controls * 20 < data.length;
    }

    private static boolean starts(byte[] data, byte[] prefix) {
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
