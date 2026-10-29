package com.jxzheng.whisper.drivers;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.jxzheng.whisper.analysis.ChiSquareAnalyzer;
import com.jxzheng.whisper.analysis.RsAnalyzer;
import com.jxzheng.whisper.carrier.JpegCoefficients;
import com.jxzheng.whisper.crypto.CipherRegistry;
import com.jxzheng.whisper.crypto.Envelope;
import com.jxzheng.whisper.crypto.KdfProfile;
import com.jxzheng.whisper.media.ImageFormats;
import com.jxzheng.whisper.ops.AnalysisFormat;
import com.jxzheng.whisper.ops.PayloadOps;
import com.jxzheng.whisper.ops.StegoOperations;
import com.jxzheng.whisper.ops.StegoOperations.Report;
import com.jxzheng.whisper.payload.ImageFit;
import com.jxzheng.whisper.payload.MimeSniffer;
import com.jxzheng.whisper.payload.NameSanitizer;
import com.jxzheng.whisper.payload.PayloadFrame;
import com.jxzheng.whisper.payload.PayloadKind;
import com.jxzheng.whisper.schemes.RatePreset;
import com.jxzheng.whisper.schemes.SchemeRegistry;

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
        } catch (OutOfMemoryError e) {
            if (kdfHigh(commandArgs)) {
                if ("extract".equals(command)) {
                    System.err.println("Out of memory. The sender used the high Argon2id profile. "
                            + "Rerun with more heap, for example JAVA_TOOL_OPTIONS=-Xmx3g.");
                } else {
                    System.err.println(
                            "Out of memory. The high Argon2id profile needs about 2 GiB of RAM. Use --kdf default.");
                }
            } else {
                System.err.println("Out of memory. Give Java more heap, or use a smaller cover.");
            }
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

        return runModernEmbed(cl);
    }

    private int runExtract(String[] args) throws ParseException, Exception {
        CommandLine cl = new DefaultParser().parse(options, args);
        if (cl.hasOption("help")) {
            printHelp();
            return 0;
        }

        return runModernExtract(cl);
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
     * Spaces are a real stego key. A missing or empty {@code -k} is the public walk.
     * Omit {@code -E} and this passphrase is also the AEAD key.
     */
    private static String stegoKey(CommandLine cl) {
        if (!cl.hasOption("key")) {
            return "";
        }
        String value = cl.getOptionValue("key");
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

                Stego output is PNG or BMP for HILL, and JPEG for the JPEG scheme.
                """,
                options,
                """
                New embeds use ChaCha20-Poly1305 unless --cipher says otherwise.
                Omit -E / --encrypt-key to derive that key from the stego passphrase (-k).
                Pass -E for a separate encryption passphrase. A blank passphrase is not confidential.
                Legacy AES-128 (-c aes) cannot be used to embed. Extract still reads 0.9 images.
                Pass --encrypt-key for those encrypted legacy files.

                Examples:
                  whisper embed -i cover.png -o stego.png -m "hello" -k "a real passphrase"
                  whisper embed -i cover.png -o stego.png -m "hello" -k walk-secret -E cipher-secret
                  whisper extract -i stego.png -k "a real passphrase"
                  whisper extract -i old.png -k walk-secret --encrypt-key cipher-secret
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
                .desc("Read a UTF-8 message to embed from a file").build());
        options.addOption(Option.builder("s").longOpt("scheme").hasArg().argName("name")
                .desc("Steganography scheme (default: auto)").build());
        options.addOption(Option.builder("c").longOpt("cipher").hasArg().argName("name")
                .desc("Cipher for new embeds (default: chacha20-poly1305). "
                        + "On extract, aes is accepted as a legacy AES-128 hint.").build());
        options.addOption(Option.builder("e").longOpt("encrypt")
                .desc("On extract without -E, try the public empty legacy AES-128 key. "
                        + "New embeds are encrypted with the selected cipher. "
                        + "Omit -E to use the stego passphrase as the AEAD key.").build());
        options.addOption(Option.builder("k").longOpt("key").hasArg().argName("key")
                .desc("Stego / pixel-walk key. Optional. Omitted or empty uses the public "
                        + "empty-string walk.").build());
        options.addOption(Option.builder("E").longOpt("encrypt-key").hasArg().argName("key")
                .desc("Separate encryption passphrase. Omitted means the stego passphrase is also the AEAD key. "
                        + "Required to decrypt legacy AES-128 payloads. The flag name is encrypt-key.").build());
        options.addOption(Option.builder("h").longOpt("help").desc("Show usage information").build());
        options.addOption(Option.builder().longOpt("file").hasArg().argName("file")
                .desc("Any file to hide").build());
        options.addOption(Option.builder().longOpt("image").hasArg().argName("file")
                .desc("Image to hide").build());
        options.addOption(Option.builder().longOpt("fit-quality").hasArg().argName("q")
                .desc("Starting JPEG quality (40-95). Steps down, then shrinks the longest edge, "
                        + "until the hidden image fits the cover").build());
        options.addOption(Option.builder().longOpt("fit-max-edge").hasArg().argName("px")
                .desc("Starting longest edge of a fitted hidden image. Shrinks further until it fits").build());
        options.addOption(Option.builder().longOpt("preset").hasArg().argName("name")
                .desc("Stealth preset: standard, low, or paranoid").build());
        options.addOption(Option.builder().longOpt("kdf").hasArg().argName("name")
                .desc("Argon2id profile: default or high").build());
        options.addOption(Option.builder().longOpt("keep-metadata")
                .desc("Keep safe metadata chunks").build());
        options.addOption(Option.builder().longOpt("out-dir").hasArg().argName("dir")
                .desc("Directory for an extracted file or image").build());
        return options;
    }

    private int runModernEmbed(CommandLine cl) throws Exception {
        Path input = requiredPath(cl, "input-file");
        Path output = requiredPath(cl, "output-file");
        String scheme = cl.getOptionValue("scheme", "auto");
        Envelope.SuiteKind suite = suite(cl, true);
        String resolved = SchemeRegistry.canonical(scheme, sniffFormat(input));
        if ("zhang-tang".equals(resolved)) {
            throw new IllegalArgumentException("Zhang–Tang is decrypt-only. New embeds use hill-stc or uerd-stc.");
        }
        if (!cl.hasOption("keep-metadata") && "jpeg".equals(sniffFormat(input))) {
            String turned = JpegCoefficients.orientationWarning(Files.readAllBytes(input));
            if (turned != null) {
                System.err.println(turned);
            }
        }
        String ext = ImageFormats.extensionOf(output);
        if ("uerd-stc".equals(resolved)) {
            if (!ext.equals("jpg") && !ext.equals("jpeg")) {
                throw new IllegalArgumentException("uerd-stc writes a JPEG. Use a .jpg output path.");
            }
        } else if (!ext.equals("png") && !ext.equals("bmp")) {
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
            mime = MimeSniffer.mime(content);
            if (cl.hasOption("fit-quality") || cl.hasOption("fit-max-edge")) {
                int quality = Integer.parseInt(cl.getOptionValue("fit-quality", "75"));
                int edge = Integer.parseInt(cl.getOptionValue("fit-max-edge", "0"));
                int room = PayloadOps.fitRoom(
                        Files.readAllBytes(input), scheme,
                        RatePreset.fromId(cl.getOptionValue("preset", "standard")), name);
                ImageFit.Result fit = ImageFit.fit(content, quality, edge, room);
                content = fit.bytes();
                reencoded = true;
                mime = "image/jpeg";
                name = PayloadFrame.storedName(PayloadKind.IMAGE, name, mime, true);
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
        } else if (cl.hasOption("message-file")) {
            content = Files.readString(Path.of(cl.getOptionValue("message-file"))).getBytes(StandardCharsets.UTF_8);
            kind = PayloadKind.TEXT;
            mime = "text/plain";
        } else if (cl.hasOption("message")) {
            content = cl.getOptionValue("message").getBytes(StandardCharsets.UTF_8);
            kind = PayloadKind.TEXT;
            mime = "text/plain";
        } else {
            throw new IllegalArgumentException("Pass --message, --message-file, --file, or --image");
        }
        char[] stego = stegoKey(cl).toCharArray();
        char[] enc = cl.hasOption("encrypt-key") ? cl.getOptionValue("encrypt-key").toCharArray() : null;
        PayloadOps.HideRequest request = new PayloadOps.HideRequest(
                input, output, kind, content, name, mime, scheme, suite,
                RatePreset.fromId(cl.getOptionValue("preset", "standard")),
                kdf(cl), stego, enc, enc != null,
                cl.hasOption("keep-metadata"), reencoded);
        if (blankKey(stego, enc, request.split())) {
            System.err.println("Note: a blank passphrase is not confidential. The payload can be derived by anyone.");
        }
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
        suite(cl, false);
        char[] stego = stegoKey(cl).toCharArray();
        char[] enc = cl.hasOption("encrypt-key") ? cl.getOptionValue("encrypt-key").toCharArray() : null;
        boolean legacyAes = isLegacyAes(cl);
        boolean legacyEmpty = enc == null && (cl.hasOption("encrypt") || legacyAes);
        boolean split = enc != null;
        PayloadOps.Outcome outcome = new PayloadOps().reveal(
                input, stego, enc, split, kdf(cl), cl.getOptionValue("scheme", "auto"), legacyEmpty,
                " If the sender used --kdf high, rerun with --kdf high.");
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
            Path dest = Path.of(cl.getOptionValue("out-dir")).resolve(name);
            try {
                Files.write(dest, outcome.content(), StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException e) {
                throw new IllegalArgumentException("Refusing to overwrite " + dest
                        + ". Pick another --out-dir or pass -o.");
            }
        } else if (outcome.kind() == PayloadKind.TEXT || outcome.legacy()) {
            System.out.write(outcome.content());
            System.out.println();
        } else {
            throw new IllegalArgumentException("Pass --out-dir or -o to save this " + outcome.kind() + " payload");
        }
        return 0;
    }

    private static boolean isLegacyAes(CommandLine cl) {
        String cipher = cl.getOptionValue("cipher");
        if (cipher == null) {
            return false;
        }
        String id = cipher.toLowerCase(Locale.ROOT).trim();
        return "aes".equals(id) || "aes-128-gcm".equals(id);
    }

    private static Envelope.SuiteKind suite(CommandLine cl, boolean embed) {
        String cipher = cl.getOptionValue("cipher");
        if (cipher != null) {
            String id = cipher.toLowerCase(Locale.ROOT).trim();
            if ("aes".equals(id) || "aes-128-gcm".equals(id)) {
                if (embed) {
                    throw new IllegalArgumentException(
                            "AES-128-GCM (-c aes) is decrypt-only. New embeds use chacha20-poly1305 or aes-256-gcm.");
                }
                return null;
            }
        }
        return CipherRegistry.suite(cipher);
    }

    private boolean kdfHigh(String[] commandArgs) {
        try {
            return kdf(new DefaultParser().parse(options, commandArgs)) == KdfProfile.HIGH;
        } catch (Exception e) {
            return false;
        }
    }

    private static KdfProfile kdf(CommandLine cl) {
        String id = cl.getOptionValue("kdf", "default");
        if ("test".equalsIgnoreCase(id) && !Boolean.getBoolean("whisper.kdf.test")) {
            throw new IllegalArgumentException(
                    "--kdf test is not a production profile. It is available only when -Dwhisper.kdf.test=true is set.");
        }
        return KdfProfile.fromId(id);
    }

    private static boolean blankKey(char[] stego, char[] enc, boolean split) {
        if (split) {
            return enc == null || enc.length == 0;
        }
        return stego == null || stego.length == 0;
    }

    private static String sniffFormat(Path path) throws IOException {
        byte[] prefix = new byte[3];
        int n;
        try (var in = Files.newInputStream(path)) {
            n = in.read(prefix);
        }
        if (n >= 3 && (prefix[0] & 0xff) == 0xff && (prefix[1] & 0xff) == 0xd8) {
            return "jpeg";
        }
        if (n >= 2 && prefix[0] == 'B' && prefix[1] == 'M') {
            return "bmp";
        }
        return "png";
    }
}
