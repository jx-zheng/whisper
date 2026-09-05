package com.jxzheng.whisper.drivers;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.jxzheng.whisper.analysis.ChiSquareAnalyzer;
import com.jxzheng.whisper.analysis.RsAnalyzer;
import com.jxzheng.whisper.encryption.AesCipher;
import com.jxzheng.whisper.encryption.CipherService;
import com.jxzheng.whisper.exceptions.EncryptionException;
import com.jxzheng.whisper.media.ImageFormats;
import com.jxzheng.whisper.schemes.AbstractScheme;
import com.jxzheng.whisper.schemes.ZhangTangScheme;

/**
 * Command-line front-end for embed, extract, and statistical analysis.
 *
 * <pre>
 *   whisper embed   -i cover.png -o stego.png -m "secret" -k passphrase [-e]
 *   whisper extract -i stego.png -k passphrase [-e] [-o message.txt]
 *   whisper analyze -i stego.png
 * </pre>
 */
public class CliDriver {

    private static final String DEFAULT_SCHEME = "zhang-tang";
    private static final String DEFAULT_CIPHER = "aes";

    private final Options options;
    private final HelpFormatter helpFormatter = new HelpFormatter();

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

    private int runEmbed(String[] args) throws ParseException, IOException, EncryptionException {
        CommandLine cl = new DefaultParser().parse(options, args);
        if (cl.hasOption("help")) {
            printHelp();
            return 0;
        }

        Path inputPath = requiredPath(cl, "input-file");
        Path outputPath = requiredPath(cl, "output-file");
        String key = requiredOption(cl, "key");
        String message = resolveMessage(cl);
        boolean encrypt = cl.hasOption("encrypt");

        ImageFormats.requireLosslessOutput(outputPath);
        String coverWarning = ImageFormats.lossyInputWarning(inputPath);
        if (coverWarning != null) {
            System.err.println(coverWarning);
        }

        BufferedImage cover = ImageIO.read(inputPath.toFile());
        if (cover == null) {
            throw new IOException("Could not read image: " + inputPath);
        }

        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        if (encrypt) {
            payload = createCipher(cl).encrypt(payload, key);
        }

        AbstractScheme scheme = createScheme(cl, cover, key);
        BufferedImage stego = scheme.embedMessage(payload);

        String format = ImageFormats.formatOf(outputPath);
        if (!ImageIO.write(stego, format, outputPath.toFile())) {
            throw new IOException("No ImageIO writer for format: " + format);
        }
        System.out.println("Embedded " + payload.length + " byte(s) into " + outputPath);
        return 0;
    }

    private int runExtract(String[] args) throws ParseException, IOException, EncryptionException {
        CommandLine cl = new DefaultParser().parse(options, args);
        if (cl.hasOption("help")) {
            printHelp();
            return 0;
        }

        Path inputPath = requiredPath(cl, "input-file");
        String key = requiredOption(cl, "key");
        boolean decrypt = cl.hasOption("encrypt");

        BufferedImage stego = ImageIO.read(inputPath.toFile());
        if (stego == null) {
            throw new IOException("Could not read image: " + inputPath);
        }

        AbstractScheme scheme = createScheme(cl, stego, key);
        byte[] payload = scheme.extractMessage();
        if (decrypt) {
            payload = createCipher(cl).decrypt(payload, key);
        }

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
        BufferedImage image = ImageIO.read(inputPath.toFile());
        if (image == null) {
            throw new IOException("Could not read image: " + inputPath);
        }

        ChiSquareAnalyzer.Result chi = new ChiSquareAnalyzer().analyze(image);
        RsAnalyzer.Result rs = new RsAnalyzer().analyze(image);

        System.out.printf(Locale.ROOT, "File: %s (%dx%d)%n", inputPath, image.getWidth(), image.getHeight());
        System.out.printf(Locale.ROOT,
                "Chi-square: χ²=%.4f  df=%d  p=%.6g  pairBalance=%.4f  stego-like@0.95=%s%n",
                chi.chiSquare(), chi.degreesOfFreedom(), chi.pValue(), chi.pairBalance(),
                chi.stegoLike(0.95));
        System.out.println("  (Westfeld: high p / high pairBalance ⇒ PoVs look equalized / classic-LSB-like.)");
        System.out.printf(Locale.ROOT, "RS analysis: estimated classic-LSB rate=%s%n",
                Double.isFinite(rs.estimatedRate()) ? String.format(Locale.ROOT, "%.4f", rs.estimatedRate()) : "n/a");
        System.out.printf(Locale.ROOT, "  Rm=%.4f Sm=%.4f R-m=%.4f S-m=%.4f  asymmetry=%.4f%n",
                rs.regularM(), rs.singularM(), rs.regularMinusM(), rs.singularMinusM(), rs.maskAsymmetry());
        System.out.println("""
                Heuristics only — calibrate on your cover set. Compare cover vs stego;
                Zhang–Tang typically moves these less than naive LSB at the same payload.""");
        return 0;
    }

    private static AbstractScheme createScheme(CommandLine cl, BufferedImage image, String key) {
        String schemeName = cl.getOptionValue("scheme", DEFAULT_SCHEME).toLowerCase(Locale.ROOT);
        return switch (schemeName) {
            case "zhang-tang", "zhangtang", "zt" -> new ZhangTangScheme(image, key);
            default -> throw new IllegalArgumentException("Unsupported scheme: " + schemeName);
        };
    }

    private static CipherService createCipher(CommandLine cl) {
        String cipherName = cl.getOptionValue("cipher", DEFAULT_CIPHER).toLowerCase(Locale.ROOT);
        return switch (cipherName) {
            case "aes" -> new AesCipher();
            default -> throw new IllegalArgumentException("Unsupported cipher: " + cipherName);
        };
    }

    private static String resolveMessage(CommandLine cl) throws IOException {
        if (cl.hasOption("message") && cl.hasOption("message-file")) {
            throw new IllegalArgumentException("Use either --message or --message-file, not both");
        }
        if (cl.hasOption("message")) {
            return cl.getOptionValue("message");
        }
        if (cl.hasOption("message-file")) {
            return Files.readString(Path.of(cl.getOptionValue("message-file")), StandardCharsets.UTF_8);
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

    private void printHelp() {
        helpFormatter.printHelp(
                "whisper <embed|extract|analyze> [options]",
                """
                Embed a secret message in an RGB image, extract one, or run statistical checks.

                Commands:
                  embed     Hide a message in a cover image
                  extract   Recover a message from a stego image
<<<<<<< HEAD

                Stego output must be PNG or BMP (allow-listed RGB containers).
                JPEG/WebP/GIF and other formats are rejected because they destroy LSBs.
=======
                  analyze   Run chi-square and RS steganalysis on an image
>>>>>>> cursor/rs-chi-square-checks-b4c6
                """,
                options,
                """
                Examples:
                  whisper embed -i cover.png -o stego.png -m "hello" -k secret
                  whisper extract -i stego.png -k secret
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
                .desc("Encrypt before embed / decrypt after extract").build());
        options.addOption(Option.builder("k").longOpt("key").hasArg().argName("key")
                .desc("Steganographic key (also used as cipher passphrase when -e is set)").build());
        options.addOption(Option.builder("h").longOpt("help").desc("Show usage information").build());
        return options;
    }
}
