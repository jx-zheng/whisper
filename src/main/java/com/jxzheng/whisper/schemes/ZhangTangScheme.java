package com.jxzheng.whisper.schemes;

import java.awt.Color;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.jxzheng.whisper.exceptions.CorruptOrInvalidStegoImageException;
import com.jxzheng.whisper.exceptions.MessageTooLongException;
import com.jxzheng.whisper.media.PointComparator;
import com.jxzheng.whisper.media.RgbPixel;

/**
 * Implementation of Zhang &amp; Tang's LSB-pair steganography scheme
 * ("A Novel Image Steganography Algorithm Against Statistical Analysis",
 * ICMLC 2007).
 *
 * <p>Each secret bit {@code m} is embedded into a color-channel sample {@code c}
 * relative to the previous sample {@code p} so that
 * {@code (LSB(p) + LSB(c')) mod 2 == m}, adjusting {@code c} by at most 1
 * (with overflow corrected by &plusmn;2).
 *
 * <p>Pixel selection is keyed. The length header and message body use two
 * independent sorted chains so the header can be recovered before the body
 * length is known.
 */
public class ZhangTangScheme extends AbstractScheme {

    public ZhangTangScheme(BufferedImage image, String key) {
        super(image, key);
    }

    @Override
    public BufferedImage embedMessage(byte[] rawMessage) {
        if (rawMessage.length > MAX_PAYLOAD_LENGTH) {
            throw new MessageTooLongException(
                    "Message exceeds max message length of " + MAX_PAYLOAD_LENGTH);
        }

        PixelPlan plan = planPixels(rawMessage.length);
        byte[] header = buildHeader(rawMessage.length);

        List<RgbPixel> modified = new ArrayList<>();
        modified.addAll(embedIntoChain(plan.headerPoints(), header, getImage()));
        modified.addAll(embedIntoChain(plan.bodyPoints(), rawMessage, applyPixels(getImage(), modified)));

        return applyPixels(getImage(), modified);
    }

    @Override
    public byte[] extractMessage() {
        restartRandomSequence();
        List<Point> headerPoints = sortPoints(selectPoints(HEADER_POINTS, Set.of()));
        byte[] header = extractFromChain(headerPoints, MESSAGE_HEADER_LENGTH);
        int messageLength = parseHeader(header);

        restartRandomSequence();
        PixelPlan plan = planPixels(messageLength);
        return extractFromChain(plan.bodyPoints(), messageLength);
    }

    private PixelPlan planPixels(int messageLength) {
        int bodyPixels = 1 + pixelsNeededForBytes(messageLength);
        int totalPoints = HEADER_POINTS + bodyPixels;
        int capacity = getImageWidth() * getImageHeight();
        if (totalPoints > capacity) {
            throw new MessageTooLongException(
                    "Message requires " + totalPoints + " pixels but image only has " + capacity);
        }

        Set<Point> headerSet = selectPoints(HEADER_POINTS, Set.of());
        List<Point> headerPoints = sortPoints(headerSet);
        Set<Point> bodySet = selectPoints(bodyPixels, headerSet);
        List<Point> bodyPoints = sortPoints(bodySet);
        return new PixelPlan(headerPoints, bodyPoints);
    }

    private static byte[] buildHeader(int messageLength) {
        return new byte[] {
                (byte) ((messageLength >> 8) & 0xFF),
                (byte) (messageLength & 0xFF),
                START_OF_TRANSMISSION
        };
    }

    private static int parseHeader(byte[] header) {
        if (header[2] != START_OF_TRANSMISSION) {
            throw new CorruptOrInvalidStegoImageException(
                    "Couldn't find STX byte; wrong key or not a whisper image?");
        }
        return ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
    }

    private List<RgbPixel> embedIntoChain(List<Point> points, byte[] payload, BufferedImage source) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("Point chain must not be empty");
        }

        List<RgbPixel> modifiedPixels = new ArrayList<>();
        Point firstPoint = points.get(0);
        modifiedPixels.add(new RgbPixel(firstPoint, getPointRgb(source, firstPoint)));

        int payloadBitsLength = payload.length * BITS_PER_BYTE;
        int bitIndex = 0;

        for (int i = 1; i < points.size() && bitIndex < payloadBitsLength; i++) {
            Point point = points.get(i);
            Color pointColor = getPointRgb(source, point);
            RgbPixel lastPixel = modifiedPixels.get(i - 1);

            int[] newRgb = {
                    pointColor.getRed(),
                    pointColor.getGreen(),
                    pointColor.getBlue()
            };

            for (int channel = 0; channel < RGB_COLORS.size() && bitIndex < payloadBitsLength; channel++) {
                int prevPixelColor = channelValue(lastPixel.color(), channel);
                int currentPixelColor = newRgb[channel];
                int msgBit = getNthBit(payload, bitIndex);
                newRgb[channel] = calculateNewColor(currentPixelColor, prevPixelColor, msgBit);
                bitIndex++;
            }

            modifiedPixels.add(new RgbPixel(point, new Color(newRgb[0], newRgb[1], newRgb[2])));
        }

        if (bitIndex < payloadBitsLength) {
            throw new IllegalStateException(
                    "Point chain too short to embed payload (" + bitIndex + "/" + payloadBitsLength + " bits)");
        }
        return modifiedPixels;
    }

    private byte[] extractFromChain(List<Point> points, int bytesExpected) {
        if (bytesExpected == 0) {
            return new byte[0];
        }
        if (points.size() < 2) {
            throw new CorruptOrInvalidStegoImageException("Not enough pixels to extract data");
        }

        byte[] extractedData = new byte[bytesExpected];
        List<Color> pixelColors = new ArrayList<>();
        pixelColors.add(getPointRgb(getImage(), points.get(0)));

        int bitIndex = 0;
        int byteIndex = 0;

        outer:
        for (int i = 1; i < points.size(); i++) {
            Color pointColor = getPointRgb(getImage(), points.get(i));
            pixelColors.add(pointColor);
            Color lastPixel = pixelColors.get(i - 1);

            for (int channel = 0; channel < RGB_COLORS.size(); channel++) {
                if (byteIndex >= bytesExpected) {
                    break outer;
                }

                int prevPixelColor = channelValue(lastPixel, channel);
                int currentPixelColor = channelValue(pointColor, channel);
                int extractedBit = ((prevPixelColor & 1) + (currentPixelColor & 1)) % 2;
                extractedData[byteIndex] = (byte) ((extractedData[byteIndex] << 1) | extractedBit);
                bitIndex++;

                if (bitIndex == BITS_PER_BYTE) {
                    bitIndex = 0;
                    byteIndex++;
                }
            }
        }

        if (byteIndex < bytesExpected) {
            throw new CorruptOrInvalidStegoImageException(
                    "Expected " + bytesExpected + " bytes but only recovered " + byteIndex);
        }
        return extractedData;
    }

    private static int channelValue(Color color, int channel) {
        return switch (channel) {
            case 0 -> color.getRed();
            case 1 -> color.getGreen();
            case 2 -> color.getBlue();
            default -> throw new IllegalArgumentException("Invalid color channel: " + channel);
        };
    }

    private List<Point> sortPoints(Set<Point> points) {
        List<Point> sortedPoints = new ArrayList<>(points);
        Comparator<Point> pointComparator = new PointComparator();
        Collections.sort(sortedPoints, pointComparator);
        return sortedPoints;
    }

    private BufferedImage applyPixels(BufferedImage source, List<RgbPixel> modifiedPixels) {
        BufferedImage copy =
                new BufferedImage(getImageWidth(), getImageHeight(), BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < getImageHeight(); y++) {
            for (int x = 0; x < getImageWidth(); x++) {
                copy.setRGB(x, y, source.getRGB(x, y));
            }
        }
        for (RgbPixel pixel : modifiedPixels) {
            copy.setRGB(pixel.point().x, pixel.point().y, pixel.color().getRGB());
        }
        return copy;
    }

    /**
     * Zhang-Tang adjustment: enforce {@code (LSB(prev) + LSB(new)) mod 2 == msgBit}
     * by adding {@code msgBit - (LSB(prev)+LSB(curr)) mod 2} to the current sample,
     * then correcting overflow/underflow by &plusmn;2.
     */
    static int calculateNewColor(int currentColor, int prevColor, int msgBit) {
        int prevColorLsb = prevColor & 1;
        int currentColorLsb = currentColor & 1;
        int newColor = currentColor + msgBit - (prevColorLsb + currentColorLsb) % 2;
        if (newColor > 255) {
            newColor -= 2;
        }
        if (newColor < 0) {
            newColor += 2;
        }
        return newColor;
    }

    private static Color getPointRgb(BufferedImage image, Point point) {
        int rgba = image.getRGB(point.x, point.y);
        int red = (rgba >>> 16) & 0xFF;
        int green = (rgba >>> 8) & 0xFF;
        int blue = rgba & 0xFF;
        return new Color(red, green, blue);
    }

    static int getNthBit(byte[] bytes, int n) {
        int byteIndex = n / BITS_PER_BYTE;
        if (byteIndex >= bytes.length) {
            throw new IllegalArgumentException("Bit requested is out of bounds");
        }
        int bitIndex = n % BITS_PER_BYTE;
        return (bytes[byteIndex] >> (7 - bitIndex)) & 1;
    }

    static int pixelsNeededForBytes(int byteLength) {
        int bitsInMessage = byteLength * BITS_PER_BYTE;
        return (int) Math.ceil(bitsInMessage / (double) USABLE_BITS_PER_PIXEL);
    }

    private Set<Point> selectPoints(int pixelsNeeded, Set<Point> excluded) {
        Set<Point> selectedPoints = new HashSet<>();
        int imageWidth = getImageWidth();
        int imageHeight = getImageHeight();
        int capacity = imageWidth * imageHeight - excluded.size();
        if (pixelsNeeded > capacity) {
            throw new MessageTooLongException(
                    "Need " + pixelsNeeded + " free pixels but only " + capacity + " remain");
        }

        while (selectedPoints.size() < pixelsNeeded) {
            int x = getRandom().nextInt(imageWidth);
            int y = getRandom().nextInt(imageHeight);
            Point pixel = new Point(x, y);
            if (!excluded.contains(pixel)) {
                selectedPoints.add(pixel);
            }
        }
        return selectedPoints;
    }

    private record PixelPlan(List<Point> headerPoints, List<Point> bodyPoints) {
    }
}
