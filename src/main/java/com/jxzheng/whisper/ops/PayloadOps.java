package com.jxzheng.whisper.ops;

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
import com.jxzheng.whisper.payload.ImageFit;
import com.jxzheng.whisper.payload.MimeSniffer;
import com.jxzheng.whisper.payload.NameSanitizer;
import com.jxzheng.whisper.payload.PayloadFormatException;
import com.jxzheng.whisper.payload.PayloadFrame;
import com.jxzheng.whisper.payload.PayloadKind;
import com.jxzheng.whisper.schemes.EmbedOptions;
import com.jxzheng.whisper.schemes.RatePreset;
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
        String scheme = canonical(request.schemeId(), format);
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
            revealed = confidentiality(revealed, request.stegoPass(), request.encPass(), request.split(), request.suite());
            AtomicFiles.write(request.output(), stego);
            return revealed;
        } finally {
            Secrets.wipe(sealed.visitSeed());
            Secrets.wipe(request.stegoPass());
            Secrets.wipe(request.encPass());
        }
    }

    public Outcome reveal(Path input, char[] stego, char[] enc, boolean split, KdfProfile profile, String schemeId) throws Exception {
        byte[] file = Files.readAllBytes(input);
        String format = sniff(file);
        String scheme = schemeId == null || schemeId.isBlank() || "auto".equalsIgnoreCase(schemeId)
                ? "auto"
                : canonical(schemeId, format);
        try {
            Outcome outcome;
            if ("auto".equals(scheme)) {
                if ("jpeg".equals(format)) {
                    outcome = revealUerd(file, stego, enc, split, profile);
                } else {
                    Outcome zhang = revealZhang(file, stego, enc, split, profile, true);
                    outcome = zhang.ok() || zhang.exitCode() == 2
                            ? zhang
                            : revealHill(file, format, stego, enc, split, profile);
                }
            } else {
                outcome = switch (scheme) {
                    case "hill-stc" -> revealHill(file, format, stego, enc, split, profile);
                    case "uerd-stc" -> revealUerd(file, stego, enc, split, profile);
                    default -> revealZhang(file, stego, enc, split, profile, true);
                };
            }
            return confidentiality(outcome, stego, enc, split, Envelope.SuiteKind.CHACHA);
        } finally {
            Secrets.wipe(stego);
            Secrets.wipe(enc);
        }
    }

    private byte[] embed(String scheme, byte[] cover, String format, byte[] envelope, byte[] visit, HideRequest request)
            throws Exception {
        EmbedOptions options = new EmbedOptions(
                new String(request.stegoPass() == null ? new char[0] : request.stegoPass()),
                request.preset(), request.keepMetadata(), "bmp".equals(format) && "hill-stc".equals(scheme) ? "bmp" : "png");
        return switch (scheme) {
            case "hill-stc" -> {
                RasterImage image = "bmp".equals(format) ? BmpCodec.read(cover) : pngOrConvert(cover);
                yield hill.embed(Carrier.raster(image, cover, format), envelope, visit, options, Progress.NONE).encoded();
            }
            case "uerd-stc" -> uerd.embed(Carrier.encoded(cover, "jpg"), envelope, visit,
                    new EmbedOptions(options.stegoKey(), request.preset(), request.keepMetadata(), "jpg"), Progress.NONE).encoded();
            default -> zhangEmbed(cover, envelope, options.stegoKey());
        };
    }

    private static byte[] zhangEmbed(byte[] cover, byte[] envelope, String key) throws Exception {
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(cover));
        if (image == null) {
            throw new java.io.IOException("Could not read the cover");
        }
        var stego = new ZhangTangScheme(image, key).embedMessage(envelope);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (!javax.imageio.ImageIO.write(stego, "png", out)) {
            throw new java.io.IOException("Could not write the stego PNG");
        }
        return out.toByteArray();
    }

    private Outcome revealBytes(byte[] stego, char[] pass, char[] enc, boolean split, KdfProfile profile, String scheme) throws Exception {
        return switch (scheme) {
            case "hill-stc" -> revealHill(stego, sniff(stego), Secrets.copy(pass), Secrets.copy(enc), split, profile);
            case "uerd-stc" -> revealUerd(stego, Secrets.copy(pass), Secrets.copy(enc), split, profile);
            default -> revealZhang(stego, Secrets.copy(pass), Secrets.copy(enc), split, profile, false);
        };
    }

    private Outcome revealHill(byte[] file, String format, char[] stego, char[] enc, boolean split, KdfProfile profile) {
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
        return fail(3, VerifyMessages.NOTHING_FOUND);
    }

    private Outcome revealUerd(byte[] file, char[] stego, char[] enc, boolean split, KdfProfile profile) {
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
        return fail(3, VerifyMessages.NOTHING_FOUND);
    }

    private Outcome tryFramed(
            int[] samples, char[] stego, char[] enc, boolean split, KdfProfile profile, RatePreset preset,
            KeyCache cache, String schemeId, String cipherLabel) {
        try {
            byte[] salt = ParityFraming.readPublic(samples, 10, preset.rate());
            byte[] master = cache.master("stego", stego, null, profile, salt);
            byte[] visit = Hkdf.expand(master, null, KeySchedule.INFO_STEGO, 32);
            try {
                byte[] blob = ParityFraming.extract(samples, visit, 10, preset.rate());
                return openBlob(blob, master, salt, stego, enc, split, profile, schemeId, cipherLabel);
            } finally {
                Secrets.wipe(visit);
                Secrets.wipe(master);
            }
        } catch (MessageTooLongException | IllegalArgumentException e) {
            return null;
        }
    }

    private Outcome revealZhang(byte[] file, char[] stego, char[] enc, boolean split, KdfProfile profile, boolean allowLegacy) {
        try {
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(file));
            if (image == null) {
                return fail(3, VerifyMessages.NOTHING_FOUND);
            }
            byte[] blob = new ZhangTangScheme(image, new String(stego == null ? new char[0] : stego)).extractMessage();
            Outcome opened = openBlob(blob, null, null, stego, enc, split, profile, "zhang-tang", "Legacy (high capacity)");
            if (opened == null) {
                return allowLegacy ? legacyPayload(blob, stego, enc) : fail(3, VerifyMessages.NOTHING_FOUND);
            }
            return opened;
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
            String message = opened.suite() == Envelope.SuiteKind.INTEGRITY
                    ? VerifyMessages.verifiedNotEncrypted(hex(frame.sha256()))
                    : VerifyMessages.verified(cipher, hex(frame.sha256()));
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
     * Bytes that are not a v2 envelope. Encrypted 0.9 payloads are decrypted with
     * the encryption passphrase. Ciphertext is never returned as a success.
     */
    private static Outcome legacyPayload(byte[] blob, char[] stego, char[] enc) {
        if (enc != null) {
            try {
                byte[] plain = new LegacyAes128GcmPbkdf2().decrypt(blob, Secrets.copy(enc));
                return new Outcome(true, 0, VerifyMessages.LEGACY_ENCRYPTED, plain, sniffKind(plain),
                        safeName("", plain), "", "zhang-tang", "legacy-aes", true, false);
            } catch (EncryptionException e) {
                if (!MimeSniffer.printableText(blob)) {
                    return fail(2, "Legacy AES-GCM decryption failed. Check the encryption passphrase. Ciphertext is not shown.");
                }
            }
        } else if (blob.length > 44 && !MimeSniffer.printableText(blob)) {
            return fail(2, "This looks like a legacy encrypted payload. Pass the encryption passphrase (-E). Ciphertext is not shown.");
        }
        if (!MimeSniffer.printableText(blob)) {
            return fail(2, "Unverified legacy bytes are not text and did not decrypt. Nothing is shown.");
        }
        return new Outcome(true, 0, VerifyMessages.LEGACY, blob, sniffKind(blob), "whisper-payload.bin", "",
                "zhang-tang", "legacy", true, false);
    }

    private static Outcome confidentiality(Outcome outcome, char[] stego, char[] enc, boolean split, Envelope.SuiteKind suite) {
        if (outcome == null || !outcome.ok() || outcome.legacy() || suite == Envelope.SuiteKind.INTEGRITY) {
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
        try {
            return PngCodec.decode(cover);
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            if (message.contains("paletted") || message.contains("interlaced") || message.contains("bit depth")) {
                return PngCodec.decode(CoverConverter.toEightBitPng(cover));
            }
            throw ex;
        }
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
            return "This payload used a different KDF profile. High needs about 2 GiB of RAM; retry with --kdf high.";
        }
        return error.suiteBits() == 2 ? VerifyMessages.INTEGRITY_FAIL : VerifyMessages.WRONG_ENCRYPTION;
    }

    private static Outcome fail(int code, String message) {
        return new Outcome(false, code, message, null, null, null, null, null, null, false, false);
    }

    private static String canonical(String scheme, String format) {
        if (scheme == null || scheme.isBlank() || "auto".equalsIgnoreCase(scheme)) {
            return "jpeg".equals(format) ? "uerd-stc" : "hill-stc";
        }
        return switch (scheme.toLowerCase(java.util.Locale.ROOT)) {
            case "hill", "hill-stc" -> "hill-stc";
            case "uerd", "uerd-stc", "jpeg" -> "uerd-stc";
            case "zhang-tang", "zhangtang", "zt" -> "zhang-tang";
            default -> throw new IllegalArgumentException("Unsupported scheme: " + scheme);
        };
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
