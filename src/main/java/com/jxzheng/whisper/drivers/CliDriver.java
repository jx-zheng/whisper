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
import com.jxzheng.whisper.media.ImageFormats;
import com.jxzheng.whisper.ops.AnalysisFormat;
import com.jxzheng.whisper.ops.StegoOperations;
import com.jxzheng.whisper.ops.StegoOperations.EmbedResult;
import com.jxzheng.whisper.ops.StegoOperations.Report;

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
        return options;
    }
}
