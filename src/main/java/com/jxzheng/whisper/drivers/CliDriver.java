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
 *   whisper embed   -i cover.png -o stego.png -m "secret" -k passphrase [-e]
 *   whisper extract -i stego.png -k passphrase [-e] [-o message.txt]
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
        String key = requiredKey(cl);
        byte[] message = resolveMessageBytes(cl);
        boolean encrypt = cl.hasOption("encrypt");

        ImageFormats.requireLosslessOutput(outputPath);
        String coverWarning = ImageFormats.lossyInputWarning(inputPath);
        if (coverWarning != null) {
            System.err.println(coverWarning);
        }

        BufferedImage cover = StegoOperations.readImage(inputPath);
        EmbedResult result = operations.embed(
                cover,
                message,
                key,
                encrypt,
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
        String key = requiredKey(cl);
        boolean decrypt = cl.hasOption("encrypt");

        BufferedImage stego = StegoOperations.readImage(inputPath);
        byte[] payload = operations.extract(
                stego,
                key,
                decrypt,
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

    /** Spaces are a real passphrase. Only a missing or empty {@code -k} is rejected. */
    private static String requiredKey(CommandLine cl) {
        String value = cl.getOptionValue("key");
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing required option: --key");
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
                  analyze   Run chi-square and RS steganalysis on an image

                Stego output must be PNG or BMP (allow-listed RGB containers).
                JPEG/WebP/GIF and other formats are rejected because they destroy LSBs.
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
                .desc("Encrypt message payload before embed / decrypt after extract").build());
        options.addOption(Option.builder("k").longOpt("key").hasArg().argName("key")
                .desc("Steganographic key (also used as cipher passphrase when -e is set)").build());
        options.addOption(Option.builder("h").longOpt("help").desc("Show usage information").build());
        return options;
    }
}
