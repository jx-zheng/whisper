package com.jxzheng.whisper.drivers;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.jxzheng.whisper.analysis.ChiSquareAnalyzer;
import com.jxzheng.whisper.analysis.RsAnalyzer;
import com.jxzheng.whisper.carrier.BmpCodec;
import com.jxzheng.whisper.carrier.JpegCoefficients;
import com.jxzheng.whisper.carrier.PngCodec;
import com.jxzheng.whisper.carrier.RasterImage;
import com.jxzheng.whisper.crypto.Envelope;
import com.jxzheng.whisper.crypto.KdfProfile;
import com.jxzheng.whisper.media.ImageFormats;
import com.jxzheng.whisper.ops.AnalysisFormat;
import com.jxzheng.whisper.ops.PayloadOps;
import com.jxzheng.whisper.ops.StegoOperations;
import com.jxzheng.whisper.ops.StegoOperations.EmbedResult;
import com.jxzheng.whisper.ops.StegoOperations.Report;
import com.jxzheng.whisper.payload.ImageFit;
import com.jxzheng.whisper.payload.MimeSniffer;
import com.jxzheng.whisper.payload.NameSanitizer;
import com.jxzheng.whisper.payload.PayloadKind;
import com.jxzheng.whisper.schemes.RatePreset;
import com.jxzheng.whisper.schemes.framing.ParityFraming;

/**
 * Command-line front-end for embed, extract, and statistical analysis.
 *
 * <pre>
 *   whisper embed   -i cover.png -o stego.png -m "secret" [-k stego-key] [-e -E encrypt-key]
 *   whisper extract -i stego.png [-k stego-key] [-e -E encrypt-key] [-o message.txt]
 *   whisper analyze -i stego.png
 * </pre>
 */
public class CliDriver {

    private final Options options;
    private final HelpFormatter helpFormatter = new HelpFormatter();
    private final StegoOperations operations = new StegoOperations();

    public CliDriver() {
        this.options = buildOptions();
    }

    public int run(String[] args) {
        if (args.length == 0) {
            printHelp();
            return 1;
        }

        String command = args[0].toLowerCase(Locale.ROOT);
        String[] commandArgs = java.util.Arrays.copyOfRange(args, 1, args.length);

        try {
            return switch (command) {
                case "embed" -> runEmbed(commandArgs);
                case "extract" -> runExtract(commandArgs);
                case "inspect" -> runInspect(commandArgs);
                case "analyze" -> runAnalyze(commandArgs);
                case "help", "-h", "--help" -> {
                    printHelp();
                    yield 0;
                }
                default -> {
                    System.err.println("Unknown command: " + command);
                    printHelp();
                    yield 1;
                }
            };
        } catch (ParseException e) {
            System.err.println("Invalid arguments: " + e.getMessage());
            printHelp();
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private int runEmbed(String[] args) throws ParseException, Exception {
        CommandLine cl = new DefaultParser().parse(options, args);
        if (cl.hasOption("help")) {
            printHelp();
            return 0;
        }

        if (modern(cl)) {
            return runModernEmbed(cl);
        }
        Path inputPath = requiredPath(cl, "input-file");
        Path outputPath = requiredPath(cl, "output-file");
        String stegoKey = stegoKey(cl);
        byte[] message = resolveMessageBytes(cl);
        boolean encrypt = cl.hasOption("encrypt");
        String encryptKey = encryptKey(cl, encrypt);

        ImageFormats.requireLosslessOutput(outputPath);
        String coverWarning = ImageFormats.lossyInputWarning(inputPath);
        if (coverWarning != null) {
            System.err.println(coverWarning);
        }

        BufferedImage cover = StegoOperations.readImage(inputPath);
        EmbedResult result = operations.embed(
                cover,
                message,
                stegoKey,
                encrypt,
                encryptKey,
                cl.getOptionValue("scheme", StegoOperations.DEFAULT_SCHEME),
                cl.getOptionValue("cipher", StegoOperations.DEFAULT_CIPHER));
        StegoOperations.writeStego(result.image(), outputPath);
        System.out.println("Embedded " + result.payloadBytes() + " byte(s) into " + outputPath);
        return 0;
    }

    private int runExtract(String[] args) throws ParseException, Exception {
        CommandLine cl = new DefaultParser().parse(options, args);
        if (cl.hasOption("help")) {
            printHelp();
            return 0;
        }

        if (modern(cl)) {
            return runModernExtract(cl);
        }
        Path inputPath = requiredPath(cl, "input-file");
        String stegoKey = stegoKey(cl);
        boolean decrypt = cl.hasOption("encrypt");
        String encryptKey = encryptKey(cl, decrypt);

        BufferedImage stego = StegoOperations.readImage(inputPath);
        byte[] payload = operations.extract(
                stego,
                stegoKey,
                decrypt,
                encryptKey,
                cl.getOptionValue("scheme", StegoOperations.DEFAULT_SCHEME),
                cl.getOptionValue("cipher", StegoOperations.DEFAULT_CIPHER));

        if (cl.hasOption("output-file")) {
            Path outputPath = Path.of(cl.getOptionValue("output-file"));
            Files.write(outputPath, payload);
            System.out.println("Wrote extracted message (" + payload.length + " byte(s)) to " + outputPath);
        } else {
            System.out.write(payload);
            System.out.println();
        }
        return 0;
    }

    private int runAnalyze(String[] args) throws ParseException, IOException {
        CommandLine cl = new DefaultParser().parse(options, args);
        if (cl.hasOption("help")) {
            printHelp();
            return 0;
        }

        Path inputPath = requiredPath(cl, "input-file");
        BufferedImage image = StegoOperations.readImage(inputPath);
        Report report = operations.analyze(image);
        ChiSquareAnalyzer.Result chi = report.chiSquare();
        RsAnalyzer.Result rs = report.rs();

        System.out.printf(Locale.ROOT, "File: %s (%dx%d)%n", inputPath, image.getWidth(), image.getHeight());
        System.out.printf(Locale.ROOT,
                "Chi-square: χ²=%s  df=%d  p=%s  pairBalance=%s  stego-like@0.95=%s%n",
                AnalysisFormat.fixed(chi.chiSquare()), chi.degreesOfFreedom(), AnalysisFormat.pValue(chi.pValue()),
                AnalysisFormat.fixed(chi.pairBalance()), chi.stegoLike(0.95));
        System.out.println("  (Westfeld: high p / high pairBalance ⇒ PoVs look equalized / classic-LSB-like.)");
        System.out.printf(Locale.ROOT, "RS analysis: estimated classic-LSB rate=%s%n", AnalysisFormat.rate(rs.estimatedRate()));
        System.out.printf(Locale.ROOT, "  Rm=%s Sm=%s R-m=%s S-m=%s  asymmetry=%s%n",
                AnalysisFormat.fixed(rs.regularM()), AnalysisFormat.fixed(rs.singularM()),
                AnalysisFormat.fixed(rs.regularMinusM()), AnalysisFormat.fixed(rs.singularMinusM()),
                AnalysisFormat.fixed(rs.maskAsymmetry()));
        System.out.println("""
                Heuristics only — calibrate on your cover set. Compare cover vs stego;
                Zhang–Tang typically moves these less than naive LSB at the same payload.""");
        return 0;
    }

    private static byte[] resolveMessageBytes(CommandLine cl) throws IOException {
        if (cl.hasOption("message") && cl.hasOption("message-file")) {
            throw new IllegalArgumentException("Use either --message or --message-file, not both");
        }
        if (cl.hasOption("message")) {
            return cl.getOptionValue("message").getBytes(StandardCharsets.UTF_8);
        }
        if (cl.hasOption("message-file")) {
            return StegoOperations.readMessageFile(Path.of(cl.getOptionValue("message-file")));
        }
        throw new IllegalArgumentException("Missing required option: --message or --message-file");
    }

    private static Path requiredPath(CommandLine cl, String option) {
        return Path.of(requiredOption(cl, option));
    }

    private static String requiredOption(CommandLine cl, String option) {
        String value = cl.getOptionValue(option);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option: --" + option);
        }
        return value;
    }

    /**
     * Spaces are a real stego key. A missing or empty {@code -k} selects the public
     * default walk (PBKDF2 of the empty string).
     */
    private static String stegoKey(CommandLine cl) {
        if (!cl.hasOption("key")) {
            return "";
        }
        String value = cl.getOptionValue("key");
        return value == null ? "" : value;
    }

    /**
     * Never falls back to {@code -k}. A missing or empty {@code -E} is the public
     * empty-string AES-GCM key. Omitting the flag prints a warning because that
     * key authenticates the payload and does not keep it confidential.
     */
    private static String encryptKey(CommandLine cl, boolean encrypt) {
        if (!encrypt) {
            return null;
        }
        if (!cl.hasOption("encrypt-key")) {
            System.err.println(
                    "Warning: --encrypt-key was omitted. Using the public empty-string AES-GCM key. "
                            + "The payload is authenticated but not confidential. "
                            + "The stego key (-k) is not used as the encryption passphrase.");
            return "";
        }
        String value = cl.getOptionValue("encrypt-key");
        return value == null ? "" : value;
    }

    private void printHelp() {
        helpFormatter.printHelp(
                "whisper <embed|extract|analyze> [options]",
                """
                Embed a secret message in an RGB image, extract one, or run statistical checks.

                Commands:
                  embed     Hide a message in a cover image
                  extract   Recover a message from a stego image
                  analyze   Run chi-square and RS steganalysis on an image

                Stego output must be PNG or BMP (allow-listed RGB containers).
                JPEG/WebP/GIF and other formats are rejected because they destroy LSBs.
                """,
                options,
                """
                The stego key (-k) and the encryption passphrase (-E) are independent.
                Omit -k, or pass an empty value, for the public empty-string pixel walk.
                -e encrypts only the hidden message and does not reuse -k.
                Omit -E, or pass an empty value, for the public empty-string AES-GCM key.
                That key authenticates the payload and is not confidential. Omitting -E prints a warning.
                The stego file stays a normal PNG or BMP.

                Examples:
                  whisper embed -i cover.png -o stego.png -m "hello"
                  whisper embed -i cover.png -o stego.png -m "hello" -k walk-secret -e -E cipher-secret
                  whisper extract -i stego.png
                  whisper extract -i stego.png -k walk-secret -e -E cipher-secret
                  whisper analyze -i stego.png
                """,
                true);
    }

    private static Options buildOptions() {
        Options options = new Options();
        options.addOption(Option.builder("i").longOpt("input-file").hasArg().argName("file")
                .desc("Input image file").build());
        options.addOption(Option.builder("o").longOpt("output-file").hasArg().argName("file")
                .desc("Output file (lossless stego image for embed — prefer .png; "
                        + "optional message file for extract)").build());
        options.addOption(Option.builder("m").longOpt("message").hasArg().argName("text")
                .desc("Message to embed (embed only)").build());
        options.addOption(Option.builder("f").longOpt("message-file").hasArg().argName("file")
                .desc("Read message to embed from a file (embed only)").build());
        options.addOption(Option.builder("s").longOpt("scheme").hasArg().argName("name")
                .desc("Steganography scheme (default: zhang-tang)").build());
        options.addOption(Option.builder("c").longOpt("cipher").hasArg().argName("name")
                .desc("Cipher to use with -e (default: aes)").build());
        options.addOption(Option.builder("e").longOpt("encrypt")
                .desc("Encrypt the message payload before embed / decrypt after extract. "
                        + "Does not encrypt the image. Does not reuse -k. "
                        + "Without --encrypt-key, uses the public empty-string AES-GCM key.").build());
        options.addOption(Option.builder("k").longOpt("key").hasArg().argName("key")
                .desc("Stego / pixel-walk key. Optional. Omitted or empty uses the public "
                        + "empty-string walk.").build());
        options.addOption(Option.builder("E").longOpt("encrypt-key").hasArg().argName("key")
                .desc("Encryption passphrase (PBKDF2 to AES-GCM) for the payload only. "
                        + "Not copied from -k. Omitted or empty is the public empty-string cipher key. "
                        + "Omitting it with -e prints a warning.").build());
        options.addOption(Option.builder("h").longOpt("help").desc("Show usage information").build());
        options.addOption(Option.builder().longOpt("text-file").hasArg().argName("file")
                .desc("UTF-8 text file to hide").build());
        options.addOption(Option.builder().longOpt("file").hasArg().argName("file")
                .desc("Any file to hide").build());
        options.addOption(Option.builder().longOpt("image").hasArg().argName("file")
                .desc("Image to hide").build());
        options.addOption(Option.builder().longOpt("fit-quality").hasArg().argName("q")
                .desc("Re-encode a hidden image as JPEG at this quality (40-95)").build());
        options.addOption(Option.builder().longOpt("fit-max-edge").hasArg().argName("px")
                .desc("Longest edge of a re-encoded hidden image").build());
        options.addOption(Option.builder().longOpt("preset").hasArg().argName("name")
                .desc("Stealth preset: standard, low, or paranoid").build());
        options.addOption(Option.builder().longOpt("kdf").hasArg().argName("name")
                .desc("Argon2id profile: default or high").build());
        options.addOption(Option.builder().longOpt("keep-metadata")
                .desc("Keep safe metadata chunks").build());
        options.addOption(Option.builder().longOpt("out-dir").hasArg().argName("dir")
                .desc("Directory for an extracted file or image").build());
        options.addOption(Option.builder().longOpt("json")
                .desc("Print inspect results as JSON").build());
        return options;
    }

    private int runModernEmbed(CommandLine cl) throws Exception {
        Path input = requiredPath(cl, "input-file");
        Path output = requiredPath(cl, "output-file");
        String scheme = cl.getOptionValue("scheme", "auto");
        Envelope.SuiteKind suite = suite(cl);
        if ("uerd-stc".equals(normalizeScheme(scheme, input)) || "uerd".equalsIgnoreCase(scheme) || "jpeg".equalsIgnoreCase(scheme)
                || (scheme.equals("auto") && isJpeg(input))) {
            String ext = ImageFormats.extensionOf(output);
            if (!ext.equals("jpg") && !ext.equals("jpeg")) {
                throw new IllegalArgumentException("uerd-stc writes a JPEG. Use a .jpg output path.");
            }
        } else {
            ImageFormats.requireLosslessOutput(output);
        }
        byte[] content;
        PayloadKind kind;
        String name = "";
        String mime = "";
        boolean reencoded = false;
        if (cl.hasOption("image")) {
            Path image = Path.of(cl.getOptionValue("image"));
            content = Files.readAllBytes(image);
            kind = PayloadKind.IMAGE;
            name = image.getFileName().toString();
            mime = "image/png";
            if (cl.hasOption("fit-quality") || cl.hasOption("fit-max-edge")) {
                int quality = Integer.parseInt(cl.getOptionValue("fit-quality", "75"));
                int edge = Integer.parseInt(cl.getOptionValue("fit-max-edge", "0"));
                ImageFit.Result fit = ImageFit.fit(content, quality, edge);
                content = fit.bytes();
                reencoded = true;
                mime = "image/jpeg";
                name = NameSanitizer.sanitize(name, mime);
            }
        } else if (cl.hasOption("file")) {
            Path file = Path.of(cl.getOptionValue("file"));
            long size = Files.size(file);
            if (size > 64L * 1024 * 1024) {
                throw new IllegalArgumentException("File is larger than 64 MiB");
            }
            content = Files.readAllBytes(file);
            kind = PayloadKind.FILE;
            name = file.getFileName().toString();
            mime = "application/octet-stream";
        } else if (cl.hasOption("text-file") || cl.hasOption("message-file")) {
            String flag = cl.hasOption("text-file") ? "text-file" : "message-file";
            content = Files.readString(Path.of(cl.getOptionValue(flag))).getBytes(StandardCharsets.UTF_8);
            kind = PayloadKind.TEXT;
            mime = "text/plain";
        } else if (cl.hasOption("message")) {
            content = cl.getOptionValue("message").getBytes(StandardCharsets.UTF_8);
            kind = PayloadKind.TEXT;
            mime = "text/plain";
        } else {
            throw new IllegalArgumentException("Pass --message, --text-file, --file, or --image");
        }
        char[] stego = stegoKey(cl).toCharArray();
        char[] enc = cl.hasOption("encrypt-key") ? cl.getOptionValue("encrypt-key").toCharArray() : null;
        PayloadOps.HideRequest request = new PayloadOps.HideRequest(
                input, output, kind, content, name, mime, scheme, suite,
                RatePreset.fromId(cl.getOptionValue("preset", "standard")),
                kdf(cl), stego, enc, enc != null && suite != Envelope.SuiteKind.INTEGRITY,
                cl.hasOption("keep-metadata"), reencoded);
        PayloadOps.Outcome outcome = new PayloadOps().hide(request);
        System.err.println(outcome.message());
        if (!outcome.ok()) {
            return outcome.exitCode();
        }
        System.out.println("Embedded " + content.length + " byte(s) into " + output);
        return 0;
    }

    private int runModernExtract(CommandLine cl) throws Exception {
        Path input = requiredPath(cl, "input-file");
        char[] stego = stegoKey(cl).toCharArray();
        char[] enc = cl.hasOption("encrypt-key") ? cl.getOptionValue("encrypt-key").toCharArray() : null;
        boolean split = enc != null && suite(cl) != Envelope.SuiteKind.INTEGRITY;
        PayloadOps.Outcome outcome = new PayloadOps().reveal(
                input, stego, enc, split, kdf(cl), cl.getOptionValue("scheme", "auto"));
        System.err.println(outcome.message());
        if (!outcome.ok()) {
            return outcome.exitCode();
        }
        if (cl.hasOption("output-file")) {
            Files.write(Path.of(cl.getOptionValue("output-file")), outcome.content());
        } else if (cl.hasOption("out-dir")) {
            String name = outcome.name() == null || outcome.name().isBlank()
                    ? NameSanitizer.sanitize("", MimeSniffer.mime(outcome.content()))
                    : outcome.name();
            Files.write(Path.of(cl.getOptionValue("out-dir")).resolve(name), outcome.content());
        } else if (outcome.kind() == PayloadKind.TEXT || outcome.legacy()) {
            System.out.write(outcome.content());
            System.out.println();
        } else {
            throw new IllegalArgumentException("Pass --out-dir or -o to save this " + outcome.kind() + " payload");
        }
        return 0;
    }

    private int runInspect(String[] args) throws Exception {
        CommandLine cl = new DefaultParser().parse(options, args);
        Path input = requiredPath(cl, "input-file");
        byte[] file = Files.readAllBytes(input);
        String format = PayloadOps.sniff(file);
        boolean json = cl.hasOption("json");
        StringBuilder out = new StringBuilder();
        if ("jpeg".equals(format)) {
            var jpeg = JpegCoefficients.parse(file);
            int nz = jpeg.nzac();
            if (json) {
                out.append("{\"format\":\"jpeg\",\"nzac\":").append(nz).append(",\"presets\":{");
            } else {
                out.append("JPEG ").append(jpeg.width).append('x').append(jpeg.height)
                        .append(" nonzero AC ").append(nz).append('\n');
            }
            boolean first = true;
            for (RatePreset preset : RatePreset.values()) {
                int bytes = ParityFraming.userBudget(nz, nz, preset.rate());
                if (json) {
                    if (!first) {
                        out.append(',');
                    }
                    out.append('"').append(preset.name().toLowerCase(Locale.ROOT)).append("\":").append(bytes);
                } else {
                    out.append(preset.displayName()).append(": ").append(bytes).append(" bytes\n");
                }
                first = false;
            }
            if (json) {
                out.append("}}");
            }
        } else {
            RasterImage image = "bmp".equals(format) ? BmpCodec.read(file) : PngCodec.decode(file);
            int pixels = image.width * image.height;
            if (json) {
                out.append("{\"format\":\"").append(format).append("\",\"pixels\":").append(pixels).append(",\"presets\":{");
            } else {
                out.append(format).append(' ').append(image.width).append('x').append(image.height).append('\n');
            }
            boolean first = true;
            for (RatePreset preset : RatePreset.values()) {
                int bytes = ParityFraming.userBudget(pixels, pixels, preset.rate());
                if (json) {
                    if (!first) {
                        out.append(',');
                    }
                    out.append('"').append(preset.name().toLowerCase(Locale.ROOT)).append("\":").append(bytes);
                } else {
                    out.append(preset.displayName()).append(": ").append(bytes).append(" bytes\n");
                }
                first = false;
            }
            if (json) {
                out.append("}}");
            }
        }
        System.out.print(out);
        if (!json) {
            System.out.print("");
        } else {
            System.out.println();
        }
        return 0;
    }

    private static boolean modern(CommandLine cl) {
        if (cl.hasOption("file") || cl.hasOption("image") || cl.hasOption("text-file")
                || cl.hasOption("preset") || cl.hasOption("kdf") || cl.hasOption("keep-metadata")) {
            return true;
        }
        String scheme = cl.getOptionValue("scheme");
        if (scheme != null) {
            String name = scheme.toLowerCase(Locale.ROOT);
            if (name.equals("hill-stc") || name.equals("uerd-stc") || name.equals("auto")
                    || name.equals("hill") || name.equals("uerd")) {
                return true;
            }
        }
        String cipher = cl.getOptionValue("cipher");
        if (cipher != null) {
            String name = cipher.toLowerCase(Locale.ROOT);
            return name.equals("chacha20-poly1305") || name.equals("aes-256-gcm") || name.equals("none");
        }
        return false;
    }

    private static Envelope.SuiteKind suite(CommandLine cl) {
        String cipher = cl.getOptionValue("cipher", "chacha20-poly1305");
        return switch (cipher.toLowerCase(Locale.ROOT)) {
            case "aes-256-gcm", "aes256", "aes-gcm" -> Envelope.SuiteKind.AES;
            case "none", "integrity" -> Envelope.SuiteKind.INTEGRITY;
            case "chacha20-poly1305", "chacha" -> Envelope.SuiteKind.CHACHA;
            default -> throw new IllegalArgumentException("Unsupported cipher: " + cipher);
        };
    }

    private static KdfProfile kdf(CommandLine cl) {
        return KdfProfile.fromId(cl.getOptionValue("kdf", "default"));
    }

    private static boolean isJpeg(Path path) throws java.io.IOException {
        byte[] prefix = new byte[3];
        try (var in = Files.newInputStream(path)) {
            if (in.read(prefix) < 3) {
                return false;
            }
        }
        return (prefix[0] & 0xff) == 0xff && (prefix[1] & 0xff) == 0xd8;
    }

    private static String normalizeScheme(String scheme, Path input) throws java.io.IOException {
        if (scheme == null || scheme.isBlank() || scheme.equalsIgnoreCase("auto")) {
            return isJpeg(input) ? "uerd-stc" : "hill-stc";
        }
        return scheme.toLowerCase(Locale.ROOT);
    }
}
