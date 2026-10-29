package com.jxzheng.whisper.gui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;

import com.jxzheng.whisper.crypto.KdfProfile;
import com.jxzheng.whisper.ops.PayloadOps;
import com.jxzheng.whisper.ops.StegoOperations;
import com.jxzheng.whisper.payload.MimeSniffer;
import com.jxzheng.whisper.payload.NameSanitizer;
import com.jxzheng.whisper.payload.PayloadKind;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

final class ExtractPane extends BorderPane {

    private final StegoOperations operations;
    private final StatusBar status;
    private final ImageCard imageCard = new ImageCard();
    private final TextField imageField = new TextField();
    private final PassphraseField stegoPassphrase = new PassphraseField("Optional — blank is not confidential");
    private final PassphraseField encryptPassphrase = new PassphraseField("Optional — blank is not confidential");
    private final CheckBox decryptBox = new CheckBox("Separate encryption passphrase");
    private final ComboBox<String> schemeBox;
    private final TextArea result = new TextArea();
    private final Label resultMeta = new Label("Extracted message will appear here.");
    private final Label fileTitle = new Label("File");
    private final Label fileDetail = new Label(" ");
    private final VBox fileCard = new VBox(8, fileTitle, fileDetail);
    private final ImageView imageView = new ImageView();
    private final Label imageCaption = new Label(" ");
    private final VBox imagePane = new VBox(8);
    private String suggestedName = "whisper-payload.bin";
    private final Button extractButton = new Button("Extract message");
    private final Button saveButton = new Button("Save message…");
    private final VBox imageColumn = new VBox(10);
    private final VBox form = new VBox(12);

    private int loadGeneration;
    private String loaded = "";
    private byte[] payload;
    private boolean payloadIsText = true;
    private boolean stacked;

    ExtractPane(StegoOperations operations, StatusBar status) {
        this.operations = operations;
        this.status = status;
        java.util.List<String> schemeNames = new java.util.ArrayList<>();
        schemeNames.add("auto");
        schemeNames.addAll(operations.schemes());
        schemeBox = Forms.choice(schemeNames, "auto");
        build();
        disableProperty().bind(status.busyProperty());
    }

    private void build() {
        setPadding(new Insets(18));
        imageField.setPromptText("Stego image");
        extractButton.getStyleClass().add("primary");
        saveButton.setDisable(true);
        result.setEditable(false);
        result.setWrapText(true);
        result.setPromptText("Extracted message");
        result.setPrefRowCount(8);
        resultMeta.getStyleClass().add("hint");
        resultMeta.setWrapText(true);
        result.setStyle("-fx-font-family: \"" + UiFonts.mono + "\";");

        VBox schemeField = Forms.field("Scheme", schemeBox);
        schemeField.setMinWidth(0);
        schemeField.setMaxWidth(Double.MAX_VALUE);
        VBox encryptKeyField = Forms.field("Encryption passphrase", encryptPassphrase);
        encryptKeyField.managedProperty().bind(decryptBox.selectedProperty());
        encryptKeyField.visibleProperty().bind(decryptBox.selectedProperty());
        VBox options = card("Keys", new VBox(10,
                Forms.field("Stego passphrase", stegoPassphrase),
                Forms.hint("Pixel-walk key. Blank matches an embed that left the stego passphrase empty."),
                decryptBox,
                encryptKeyField,
                schemeField,
                Forms.hint("Leave the encryption passphrase off to use the stego passphrase for both. "
                        + "Turn it on for a separate key, including legacy AES-128. "
                        + "A blank passphrase is not confidential. Ciphertext is never shown as the payload.")));
        result.setMinWidth(0);
        result.setMaxHeight(Double.MAX_VALUE);
        result.getStyleClass().add("paper");
        fileCard.getStyleClass().add("drop-tile");
        fileCard.setFillWidth(true);
        fileTitle.setWrapText(true);
        fileDetail.setWrapText(true);
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(360);
        imageView.setOnMouseClicked(event -> zoom());
        imageCaption.getStyleClass().add("hint");
        imageCaption.setWrapText(true);
        imagePane.getChildren().addAll(imageView, imageCaption);
        imagePane.getStyleClass().add("image-tile");
        fileCard.setVisible(false);
        fileCard.setManaged(false);
        imagePane.setVisible(false);
        imagePane.setManaged(false);
        StackPane viewers = new StackPane(result, fileCard, imagePane);
        viewers.setMaxWidth(Double.MAX_VALUE);
        VBox resultBody = new VBox(8, resultMeta, viewers, new HBox(8, saveButton));
        VBox.setVgrow(viewers, Priority.ALWAYS);
        VBox resultCard = card("Message", resultBody);
        VBox.setVgrow(resultBody, Priority.ALWAYS);
        HBox actions = new HBox(8, extractButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        form.getChildren().addAll(options, actions, resultCard);
        VBox.setVgrow(resultCard, Priority.ALWAYS);
        form.setMinWidth(0);

        imageCard.attachPath("Stego image", imageField);
        imageColumn.getChildren().add(imageCard);
        VBox.setVgrow(imageCard, Priority.ALWAYS);
        imageCard.setMaxHeight(Double.MAX_VALUE);
        imageColumn.setFillWidth(true);
        Forms.wrapHints(form);
        Forms.wrapHints(imageColumn);
        imageCard.setOnBrowse(this::chooseImage);
        imageCard.setOnFile(this::useImage);
        imageField.setOnAction(event -> reload(false));
        imageField.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                reload(false);
            }
        });
        extractButton.setOnAction(event -> extract());
        saveButton.setOnAction(event -> save());
        stegoPassphrase.setOnAction(event -> extract());
        encryptPassphrase.setOnAction(event -> extract());

        widthProperty().addListener((obs, previous, width) -> arrange(width.doubleValue()));
        setMinWidth(0);
        PaneLayout.apply(this, imageColumn, imageCard, form, false, 1080);
    }

    private void arrange(double width) {
        if (width <= 0) {
            return;
        }
        stacked = PaneLayout.stacked(stacked, width);
        PaneLayout.apply(this, imageColumn, imageCard, form, stacked, width);
    }

    void loadFromEmbed(Path stego, String stegoKey, String encryptKey, boolean decrypt, String scheme, String cipher) {
        imageField.setText(stego.toString());
        stegoPassphrase.setText(stegoKey);
        encryptPassphrase.setText(encryptKey);
        decryptBox.setSelected(decrypt);
        if (scheme != null && schemeBox.getItems().contains(scheme)) {
            schemeBox.setValue(scheme);
        }
        loaded = "";
        clearResult();
        status.show(StatusBar.Level.INFO,
                "Stego loaded in Extract. Both passphrases and the encryption setting were copied.");
        reload(false);
    }

    private void chooseImage() {
        var chooser = Choosers.images("Choose stego image");
        Choosers.seed(chooser, imageField.getText(), null);
        Path path = Choosers.chooseOpen(chooser, getScene() == null ? null : getScene().getWindow());
        if (path != null) {
            useImage(path);
        }
    }

    private void useImage(Path path) {
        imageField.setText(path.toString());
        loaded = "";
        reload(false);
    }

    private void reload(boolean force) {
        Path path = pathFrom(imageField.getText());
        int token = ++loadGeneration;
        if (path == null) {
            loaded = "";
            imageCard.clear("No image selected");
            clearResult();
            return;
        }
        if (!force && path.toString().equals(loaded)) {
            return;
        }
        clearResult();
        Background.start("whisper-stego", () -> {
            BufferedImage image = StegoOperations.readImage(path);
            return new Loaded(FxImages.scale(image, FxImages.PREVIEW_EDGE), image.getWidth(), image.getHeight());
        }, loadedImage -> {
            if (token != loadGeneration) {
                return;
            }
            loaded = path.toString();
            imageCard.setPreview(FxImages.toFx(loadedImage.preview()));
            imageCard.setCaption(fileName(path));
            imageCard.setDetail(loadedImage.width() + "×" + loadedImage.height());
            imageCard.setWarning(null);
        }, error -> {
            if (token != loadGeneration) {
                return;
            }
            loaded = "";
            imageCard.clear(fileName(path));
            imageCard.setWarning(error.getMessage());
            status.show(StatusBar.Level.ERROR, error.getMessage());
        });
    }

    private void extract() {
        Path input = pathFrom(imageField.getText());
        String stegoKey = stegoPassphrase.getText();
        boolean separate = decryptBox.isSelected();
        String encryptKey = separate ? encryptPassphrase.getText() : null;
        String scheme = schemeBox.getValue();
        if (input == null) {
            status.show(StatusBar.Level.ERROR, "Choose a stego image.");
            return;
        }
        clearResult();
        status.run("Extracting…", new Task<Extracted>() {
            @Override
            protected Extracted call() throws Exception {
                PayloadOps.Outcome outcome = new PayloadOps().reveal(
                        input,
                        stegoKey.toCharArray(),
                        encryptKey == null ? null : encryptKey.toCharArray(),
                        separate,
                        KdfProfile.DEFAULT,
                        scheme);
                if (!outcome.ok() || outcome.content() == null) {
                    throw new IllegalArgumentException(outcome.message());
                }
                return new Extracted(
                        outcome.content(), outcome.message(), outcome.kind(), outcome.name(), outcome.reencoded());
            }
        }, this::showPayload);
    }

    private void showPayload(Extracted extracted) {
        payload = extracted.bytes();
        PayloadKind kind = extracted.kind() == null ? PayloadKind.FILE : extracted.kind();
        String mime = MimeSniffer.mime(payload);
        suggestedName = extracted.name() == null || extracted.name().isBlank()
                ? (kind == PayloadKind.TEXT ? "message.txt" : NameSanitizer.sanitize("", mime))
                : extracted.name();
        payloadIsText = kind == PayloadKind.TEXT;
        String verified = extracted.verified();
        resultMeta.setText(verified == null ? "Recovered " + payload.length + " byte(s)." : verified);
        resultMeta.getStyleClass().removeAll("badge-ok", "badge-warn", "badge-bad");
        if (verified != null && (verified.startsWith("Unverified")
                || verified.contains("not confidential")
                || verified.contains("not encrypted"))) {
            resultMeta.getStyleClass().add("badge-warn");
        } else if (verified != null && verified.startsWith("Verified")) {
            resultMeta.getStyleClass().add("badge-ok");
        }
        if (kind == PayloadKind.IMAGE) {
            Image preview = previewImage(payload);
            if (preview == null) {
                showFile(suggestedName, payload.length, mime, "Couldn't preview this image");
            } else {
                imageView.setImage(preview);
                imageCaption.setText(imageCaption(suggestedName, preview, payload.length, extracted.reencoded()));
                showOnly(imagePane);
            }
        } else if (kind == PayloadKind.FILE) {
            showFile(suggestedName, payload.length, mime, null);
        } else {
            result.setWrapText(true);
            result.setText(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            showOnly(result);
        }
        saveButton.setDisable(false);
        status.show(StatusBar.Level.SUCCESS, resultMeta.getText());
    }

    private void showFile(String name, int size, String mime, String note) {
        fileTitle.setText(name);
        String detail = sizeText(size) + " · " + typeLabel(mime);
        if (note != null) {
            detail += "\n" + note;
        }
        if (riskyName(name)) {
            detail += "\nOnly open files from people you trust.";
        }
        fileDetail.setText(detail);
        showOnly(fileCard);
    }

    private void showOnly(javafx.scene.Node viewer) {
        for (javafx.scene.Node node : java.util.List.of(result, fileCard, imagePane)) {
            boolean on = node == viewer;
            node.setVisible(on);
            node.setManaged(on);
        }
    }

    private void zoom() {
        Image image = imageView.getImage();
        if (image == null) {
            return;
        }
        ImageView big = new ImageView(image);
        big.setPreserveRatio(true);
        double fitted = Math.min(Math.max(image.getWidth(), 1), 900);
        big.setFitWidth(fitted);
        Button actual = new Button("100%");
        actual.setOnAction(event -> {
            if (big.getFitWidth() == image.getWidth()) {
                big.setFitWidth(fitted);
                actual.setText("100%");
            } else {
                big.setFitWidth(image.getWidth());
                actual.setText("Fit");
            }
        });
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(suggestedName);
        dialog.getDialogPane().setContent(new VBox(8, big, actual));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private void save() {
        if (payload == null) {
            status.show(StatusBar.Level.ERROR, "Nothing to save yet.");
            return;
        }
        var chooser = Choosers.messageSave("Save extracted payload", payloadIsText);
        Choosers.seed(chooser, null, suggestedName);
        Path path = Choosers.chooseSave(chooser, getScene() == null ? null : getScene().getWindow());
        if (path == null) {
            return;
        }
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                status.show(StatusBar.Level.ERROR, "Output directory does not exist: " + parent);
                return;
            }
            Files.write(path, payload);
            status.show(StatusBar.Level.SUCCESS, "Saved " + payload.length + " byte(s) to " + path);
        } catch (Exception ex) {
            status.show(StatusBar.Level.ERROR, ex.getMessage() == null ? "Could not save the message." : ex.getMessage());
        }
    }

    private void clearResult() {
        if (payload != null) {
            Arrays.fill(payload, (byte) 0);
        }
        payload = null;
        payloadIsText = true;
        suggestedName = "whisper-payload.bin";
        result.clear();
        imageView.setImage(null);
        imageCaption.setText("");
        fileTitle.setText("File");
        fileDetail.setText(" ");
        resultMeta.getStyleClass().removeAll("badge-ok", "badge-warn", "badge-bad");
        resultMeta.setText("Extracted message will appear here.");
        showOnly(result);
        saveButton.setDisable(true);
    }

    /** Decode a preview, or null when the bytes are not an image or exceed the preview limits (F11). */
    private static Image previewImage(byte[] bytes) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return null;
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 20_000 || height > 20_000 || (long) width * height > 40_000_000L) {
                    return null;
                }
                int step = Math.max(1, (Math.max(width, height) + 2047) / 2048);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage decoded = reader.read(0, param);
                return decoded == null ? null : FxImages.toFx(decoded);
            } finally {
                reader.dispose();
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private static String imageCaption(String name, Image image, int bytes, boolean reencoded) {
        String caption = name + " · " + (int) image.getWidth() + "×" + (int) image.getHeight()
                + " · " + sizeText(bytes);
        if (reencoded) {
            caption += " · Resized by sender to fit";
        }
        return caption;
    }

    private static String sizeText(int bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
    }

    private static String typeLabel(String mime) {
        return switch (mime) {
            case "image/png" -> "PNG image";
            case "image/jpeg" -> "JPEG image";
            case "image/gif" -> "GIF image";
            case "image/bmp" -> "BMP image";
            case "image/webp" -> "WebP image";
            case "application/pdf" -> "PDF";
            case "application/zip" -> "ZIP archive";
            case "text/plain" -> "Text";
            default -> "File";
        };
    }

    private static boolean riskyName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : new String[] {".exe", ".app", ".sh", ".bat", ".jar", ".command", ".js", ".msi", ".dmg", ".scr"}) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static VBox card(String title, javafx.scene.Node body) {
        Label heading = new Label(title);
        heading.getStyleClass().add("card-title");
        VBox box = new VBox(10, heading, body);
        box.getStyleClass().add("card");
        return box;
    }

    private static String fileName(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    private static Path pathFrom(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Path.of(text.trim());
    }

    private record Loaded(BufferedImage preview, int width, int height) {
    }

    private record Extracted(byte[] bytes, String verified, PayloadKind kind, String name, boolean reencoded) {
    }
}
