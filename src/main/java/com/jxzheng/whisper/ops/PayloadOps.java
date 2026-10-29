package com.jxzheng.whisper.ops;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.jxzheng.whisper.carrier.BmpCodec;
import com.jxzheng.whisper.carrier.Carrier;
import com.jxzheng.whisper.carrier.CoverConverter;
import com.jxzheng.whisper.carrier.JpegCoefficients;
import com.jxzheng.whisper.carrier.PngCodec;
import com.jxzheng.whisper.carrier.RasterImage;
import com.jxzheng.whisper.crypto.AuthException;
import com.jxzheng.whisper.crypto.Compression;
import com.jxzheng.whisper.crypto.Envelope;
import com.jxzheng.whisper.crypto.Hkdf;
import com.jxzheng.whisper.crypto.KdfProfile;
import com.jxzheng.whisper.crypto.KeyCache;
import com.jxzheng.whisper.crypto.KeySchedule;
import com.jxzheng.whisper.crypto.LegacyAes128GcmPbkdf2;
import com.jxzheng.whisper.crypto.Secrets;
import com.jxzheng.whisper.exceptions.CorruptOrInvalidStegoImageException;
import com.jxzheng.whisper.exceptions.EncryptionException;
import com.jxzheng.whisper.exceptions.MessageTooLongException;
import com.jxzheng.whisper.payload.MimeSniffer;
import com.jxzheng.whisper.payload.NameSanitizer;
import com.jxzheng.whisper.payload.PayloadFormatException;
import com.jxzheng.whisper.payload.PayloadFrame;
import com.jxzheng.whisper.payload.PayloadKind;
import com.jxzheng.whisper.schemes.EmbedOptions;
import com.jxzheng.whisper.schemes.RatePreset;
import com.jxzheng.whisper.schemes.SchemeRegistry;
import com.jxzheng.whisper.schemes.ZhangTangScheme;
import com.jxzheng.whisper.schemes.adaptive.HillStcScheme;
import com.jxzheng.whisper.schemes.adaptive.UerdStcScheme;
import com.jxzheng.whisper.schemes.framing.ParityFraming;

/**
 * Hide and reveal for the v2 envelope. Legacy Zhang–Tang bytes stay on
 * {@link StegoOperations}.
 */
public final class PayloadOps {

    public record HideRequest(
            Path cover,
            Path output,
            PayloadKind kind,
            byte[] content,
            String name,
            String mime,
            String schemeId,
            Envelope.SuiteKind suite,
            RatePreset preset,
            KdfProfile profile,
            char[] stegoPass,
            char[] encPass,
            boolean split,
            boolean keepMetadata,
            boolean reencoded) {
    }

    public record Outcome(
            boolean ok,
            int exitCode,
            String message,
            byte[] content,
            PayloadKind kind,
            String name,
            String sha256,
            String schemeId,
            String cipher,
            boolean legacy,
            boolean reencoded) {
    }

    private final HillStcScheme hill = new HillStcScheme();
    private final UerdStcScheme uerd = new UerdStcScheme();

    public Outcome hide(HideRequest request) throws Exception {
        byte[] cover = Files.readAllBytes(request.cover());
        String format = sniff(cover);
        String scheme = SchemeRegistry.canonical(request.schemeId(), format);
        if ("zhang-tang".equals(scheme)) {
            throw new IllegalArgumentException(
                    "Zhang–Tang is decrypt-only. New embeds use hill-stc or uerd-stc.");
        }
        PayloadFrame.Built frame = PayloadFrame.build(
                request.kind(), request.name(), request.mime(), request.content(), request.reencoded());
        Envelope.SealRequest seal = new Envelope.SealRequest();
        seal.stegoPass = Secrets.copy(request.stegoPass());
        seal.encPass = request.split ? Secrets.copy(request.encPass()) : null;
        seal.split = request.split;
        seal.profile = request.profile();
        seal.suite = request.suite();
        seal.compress = Compression.Mode.AUTO;
        seal.contentAlreadyDense = Compression.looksCompressed(request.content());
        Envelope.Sealed sealed = Envelope.seal(seal, frame.bytes());
        try {
            byte[] stego = embed(scheme, cover, format, sealed.bytes(), sealed.visitSeed(), request);
            Outcome revealed = revealBytes(stego, request.stegoPass(), request.encPass(), request.split(), request.profile(), scheme);
            if (!revealed.ok() || !Arrays.equals(revealed.content(), request.content())) {
                return fail(2, revealed.ok() ? VerifyMessages.HASH : revealed.message());
            }
            revealed = confidentiality(revealed, request.stegoPass(), request.encPass(), request.split());
            AtomicFiles.write(request.output(), stego);
            return revealed;
        } finally {
            Secrets.wipe(sealed.visitSeed());
            Secrets.wipe(request.stegoPass());
            Secrets.wipe(request.encPass());
        }
    }

    public Outcome reveal(
            Path input, char[] stego, char[] enc, boolean split, KdfProfile profile, String schemeId,
            boolean legacyEmpty, String highProfileNote) throws Exception {
        byte[] file = Files.readAllBytes(input);
        String format = sniff(file);
        boolean auto = schemeId == null || schemeId.isBlank() || "auto".equalsIgnoreCase(schemeId);
        String scheme = auto ? "auto" : SchemeRegistry.canonical(schemeId, format);
        try {
            Outcome outcome;
            if ("auto".equals(scheme)) {
                if ("jpeg".equals(format)) {
                    outcome = revealUerd(file, stego, enc, split, profile, highProfileNote);
                } else {
                    outcome = revealAutoRaster(file, format, stego, enc, split, profile, legacyEmpty, highProfileNote);
                }
            } else {
                outcome = switch (scheme) {
                    case "hill-stc" -> revealHill(file, format, stego, enc, split, profile, highProfileNote);
                    case "uerd-stc" -> revealUerd(file, stego, enc, split, profile, highProfileNote);
                    default -> revealZhang(file, stego, enc, legacyEmpty);
                };
            }
            return confidentiality(outcome, stego, enc, split);
        } finally {
            Secrets.wipe(stego);
            Secrets.wipe(enc);
        }
    }

    private byte[] embed(String scheme, byte[] cover, String format, byte[] envelope, byte[] visit, HideRequest request)
            throws Exception {
        String outExt = com.jxzheng.whisper.media.ImageFormats.extensionOf(request.output());
        String outputFormat = outputFormat(scheme, outExt);
        EmbedOptions options = new EmbedOptions(request.preset(), request.keepMetadata(), outputFormat);
        return switch (scheme) {
            case "hill-stc" -> {
                RasterImage image = "bmp".equals(format) ? BmpCodec.read(cover) : pngOrConvert(cover);
                yield hill.embed(Carrier.raster(image, cover, format), envelope, visit, options).encoded();
            }
            case "uerd-stc" -> uerd.embed(Carrier.encoded(cover, "jpg"), envelope, visit, options).encoded();
            default -> throw new IllegalArgumentException(
                    "Zhang–Tang is decrypt-only. New embeds use hill-stc or uerd-stc.");
        };
    }

    private static String outputFormat(String scheme, String extension) {
        if ("uerd-stc".equals(scheme)) {
            if (!"jpg".equals(extension) && !"jpeg".equals(extension)) {
                throw new IllegalArgumentException("uerd-stc writes a JPEG. Use a .jpg output path.");
            }
            return "jpg";
        }
        if ("bmp".equals(extension)) {
            return "bmp";
        }
        if ("png".equals(extension)) {
            return "png";
        }
        throw new IllegalArgumentException("hill-stc writes PNG or BMP. Use a .png or .bmp output path.");
    }

    private Outcome revealBytes(byte[] stego, char[] pass, char[] enc, boolean split, KdfProfile profile, String scheme) throws Exception {
        return switch (scheme) {
            case "hill-stc" -> revealHill(stego, sniff(stego), Secrets.copy(pass), Secrets.copy(enc), split, profile, null);
            case "uerd-stc" -> revealUerd(stego, Secrets.copy(pass), Secrets.copy(enc), split, profile, null);
            default -> revealZhang(stego, Secrets.copy(pass), Secrets.copy(enc), false);
        };
    }

    private Outcome revealHill(
            byte[] file, String format, char[] stego, char[] enc, boolean split, KdfProfile profile, String highProfileNote) {
        RasterImage image;
        try {
            image = "bmp".equals(format) ? BmpCodec.read(file) : PngCodec.decode(file);
        } catch (RuntimeException e) {
            return fail(3, VerifyMessages.NOTHING_FOUND);
        }
        int[] samples = HillStcScheme.plane(image).samples();
        try (KeyCache cache = new KeyCache()) {
            for (RatePreset preset : RatePreset.values()) {
                Outcome outcome = tryFramed(samples, stego, enc, split, profile, preset, cache, "hill-stc", "Adaptive (HILL)");
                if (outcome != null) {
                    return outcome;
                }
            }
        }
        return fail(3, nothingFound(profile, highProfileNote));
    }

    private Outcome revealUerd(
            byte[] file, char[] stego, char[] enc, boolean split, KdfProfile profile, String highProfileNote) {
        JpegCoefficients jpeg;
        try {
            jpeg = JpegCoefficients.parse(file);
        } catch (RuntimeException e) {
            return fail(3, VerifyMessages.NOTHING_FOUND);
        }
        int[] samples = UerdStcScheme.ac(jpeg.luma(), null).samples();
        try (KeyCache cache = new KeyCache()) {
            for (RatePreset preset : RatePreset.values()) {
                Outcome outcome = tryFramed(samples, stego, enc, split, profile, preset, cache, "uerd-stc", "Adaptive (JPEG)");
                if (outcome != null) {
                    return outcome;
                }
            }
        }
        return fail(3, nothingFound(profile, highProfileNote));
    }

    private Outcome tryFramed(
            int[] samples, char[] stego, char[] enc, boolean split, KdfProfile profile, RatePreset preset,
            KeyCache cache, String schemeId, String cipherLabel) {
        try {
            byte[] salt = ParityFraming.readPublic(samples, 10, preset.rate());
            byte[] master = cache.master("stego", stego, profile, salt);
            byte[] visit = Hkdf.expand(master, null, KeySchedule.INFO_STEGO, 32);
            try {
                byte[] blob = ParityFraming.extract(samples, visit, 10, preset.rate());
                return openBlob(blob, master, salt, stego, enc, split, profile, schemeId, cipherLabel);
            } finally {
                Secrets.wipe(visit);
                Secrets.wipe(master);
            }
        } catch (MessageTooLongException e) {
            return null;
        }
    }

    /**
     * A Zhang–Tang hit is always the 0.9 layout. It is a miss for auto until
     * HILL also misses, so a false header does not hide a modern payload.
     */
    private Outcome revealAutoRaster(
            byte[] file, String format, char[] stego, char[] enc, boolean split, KdfProfile profile,
            boolean legacyEmpty, String highProfileNote) {
        Outcome zhang = revealZhang(file, stego, enc, legacyEmpty);
        Outcome hill = revealHill(file, format, stego, enc, split, profile, highProfileNote);
        if (hill.ok() || hill.exitCode() == 2) {
            return hill;
        }
        if (zhang.ok() || "legacy".equals(zhang.cipher())) {
            return zhang;
        }
        return hill;
    }

    private Outcome revealZhang(byte[] file, char[] stego, char[] enc, boolean legacyEmpty) {
        try {
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(file));
            if (image == null) {
                return fail(3, VerifyMessages.NOTHING_FOUND);
            }
            byte[] blob = new ZhangTangScheme(image, new String(stego == null ? new char[0] : stego)).extractMessage();
            return legacyPayload(blob, enc, legacyEmpty);
        } catch (CorruptOrInvalidStegoImageException | MessageTooLongException | java.io.IOException e) {
            return fail(3, VerifyMessages.NOTHING_FOUND);
        }
    }

    private Outcome openBlob(
            byte[] blob, byte[] master, byte[] expectedSalt, char[] stego, char[] enc, boolean split,
            KdfProfile profile, String schemeId, String label) {
        Envelope.OpenRequest open = new Envelope.OpenRequest();
        open.stegoMaster = master;
        open.expectedSalt = expectedSalt;
        open.stegoPass = master == null ? Secrets.copy(stego) : null;
        open.encPass = split ? Secrets.copy(enc) : null;
        open.split = split;
        open.profile = profile;
        Envelope.Opened opened;
        try {
            opened = Envelope.open(open, blob);
        } catch (AuthException e) {
            if (e.kind() == AuthException.Kind.SALT || e.kind() == AuthException.Kind.FORMAT) {
                return null;
            }
            return fromAuth(e);
        }
        try {
            PayloadFrame.Parsed frame = PayloadFrame.parse(opened.frame());
            String cipher = opened.suite().suite().displayName();
            String message = VerifyMessages.verified(cipher, hex(frame.sha256()));
            return new Outcome(true, 0, message, frame.content(), frame.kind(), frame.name(), hex(frame.sha256()),
                    schemeId, cipher, false, frame.reencoded());
        } catch (PayloadFormatException e) {
            Secrets.wipe(opened.frame());
            if (e.kind() == PayloadFormatException.Kind.HASH) {
                return fail(2, VerifyMessages.HASH);
            }
            return fail(2, VerifyMessages.DAMAGED);
        }
    }

    /**
     * Bytes that are not a v2 envelope. A decrypt attempt that fails never
     * returns those bytes. Unverified success requires strict UTF-8.
     */
    private static Outcome legacyPayload(byte[] blob, char[] enc, boolean legacyEmpty) {
        char[] key = enc;
        if (key == null && legacyEmpty) {
            key = new char[0];
        }
        if (key != null) {
            try {
                byte[] plain = new LegacyAes128GcmPbkdf2().decrypt(blob, Secrets.copy(key));
                return new Outcome(true, 0, VerifyMessages.LEGACY_ENCRYPTED, plain, sniffKind(plain),
                        safeName("", plain), "", "zhang-tang", "legacy-aes", true, false);
            } catch (EncryptionException e) {
                return legacyFail(2, "Legacy AES-GCM decryption failed. Check the encryption passphrase. Ciphertext is not shown.");
            }
        }
        if (validUtf8(blob)) {
            return new Outcome(true, 0, VerifyMessages.LEGACY, blob, sniffKind(blob), "whisper-payload.bin", "",
                    "zhang-tang", "legacy", true, false);
        }
        if (blob.length >= 44) {
            return legacyFail(2, "This looks like a legacy encrypted payload. Enter the encryption passphrase. Ciphertext is not shown.");
        }
        return legacyFail(2, "Unverified legacy bytes are not text and did not decrypt. Nothing is shown.");
    }

    private static boolean validUtf8(byte[] data) {
        if (data == null) {
            return false;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static Outcome confidentiality(Outcome outcome, char[] stego, char[] enc, boolean split) {
        if (outcome == null || !outcome.ok() || outcome.legacy()) {
            return outcome;
        }
        boolean secret = split ? enc != null && enc.length > 0 : stego != null && stego.length > 0;
        if (secret || outcome.message() == null || !outcome.message().startsWith("Verified ·")) {
            return outcome;
        }
        String message = "Verified, not confidential ·" + outcome.message().substring("Verified ·".length());
        return new Outcome(true, 0, message, outcome.content(), outcome.kind(), outcome.name(), outcome.sha256(),
                outcome.schemeId(), outcome.cipher(), false, outcome.reencoded());
    }

    private static RasterImage pngOrConvert(byte[] cover) throws Exception {
        if (needsRasterConversion(cover)) {
            return PngCodec.decode(CoverConverter.toEightBitPng(cover));
        }
        return PngCodec.decode(cover);
    }

    /** Indexed, interlaced, and non-8-bit PNGs are rewritten before HILL. */
    static boolean needsRasterConversion(byte[] file) {
        if (file == null || file.length < 29 || file[0] != (byte) 0x89 || file[1] != 'P') {
            return false;
        }
        if (file[12] != 'I' || file[13] != 'H' || file[14] != 'D' || file[15] != 'R') {
            return false;
        }
        int depth = file[24] & 0xff;
        int color = file[25] & 0xff;
        int interlace = file[28] & 0xff;
        if (interlace != 0 || depth != 8 || color == 3) {
            return true;
        }
        return color != 0 && color != 2 && color != 4 && color != 6;
    }

    private static PayloadKind sniffKind(byte[] bytes) {
        if (MimeSniffer.image(bytes)) {
            return PayloadKind.IMAGE;
        }
        if (MimeSniffer.printableText(bytes)) {
            return PayloadKind.TEXT;
        }
        return PayloadKind.FILE;
    }

    private static Outcome fromAuth(AuthException e) {
        return switch (e.kind()) {
            case AUTH -> fail(2, authMessage(e));
            case INCOMPLETE -> fail(2, VerifyMessages.INCOMPLETE);
            case DECOMPRESS -> fail(2, VerifyMessages.DECOMPRESS);
            case FORMAT -> fail(2, VerifyMessages.DAMAGED);
            case SALT -> fail(3, VerifyMessages.NOTHING_FOUND);
        };
    }

    private static String authMessage(AuthException error) {
        String text = error.getMessage() == null ? "" : error.getMessage();
        if (text.contains("kdf profile")) {
            return "This image may have been embedded with the high-memory key profile. Opening it needs about 2 GiB of RAM.";
        }
        return VerifyMessages.WRONG_ENCRYPTION;
    }

    private static String nothingFound(KdfProfile profile, String highProfileNote) {
        if (profile == KdfProfile.DEFAULT && highProfileNote != null && !highProfileNote.isBlank()) {
            return VerifyMessages.NOTHING_FOUND + highProfileNote;
        }
        return VerifyMessages.NOTHING_FOUND;
    }

    private static Outcome fail(int code, String message) {
        return new Outcome(false, code, message, null, null, null, null, null, null, false, false);
    }

    private static Outcome legacyFail(int code, String message) {
        return new Outcome(false, code, message, null, null, null, null, "zhang-tang", "legacy", false, false);
    }

    public static String sniff(byte[] file) {
        if (file.length >= 8 && file[0] == (byte) 0x89 && file[1] == 'P') {
            return "png";
        }
        if (file.length >= 2 && file[0] == 'B' && file[1] == 'M') {
            return "bmp";
        }
        if (file.length >= 3 && (file[0] & 0xff) == 0xff && (file[1] & 0xff) == 0xd8) {
            return "jpeg";
        }
        return "png";
    }

    public static String hex(byte[] sha) {
        return java.util.HexFormat.of().formatHex(sha);
    }

    static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static String safeName(String name, byte[] content) {
        return NameSanitizer.sanitize(name, MimeSniffer.mime(content));
    }
}
