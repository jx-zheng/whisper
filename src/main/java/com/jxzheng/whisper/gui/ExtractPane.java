package com.jxzheng.whisper.gui;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import com.jxzheng.whisper.ops.PayloadPreview;
import com.jxzheng.whisper.ops.StegoOperations;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class ExtractPane extends BorderPane {

    private final StegoOperations operations;
    private final StatusBar status;
    private final ImageCard imageCard = new ImageCard();
    private final TextField imageField = new TextField();
    private final PassphraseField stegoPassphrase = new PassphraseField("Optional — blank uses the public walk");
    private final PassphraseField encryptPassphrase = new PassphraseField("Optional — blank uses the public cipher key");
    private final CheckBox decryptBox = new CheckBox("Decrypt payload (AES-GCM)");
    private final ComboBox<String> schemeBox;
    private final ComboBox<String> cipherBox;
    private final TextArea result = new TextArea();
    private final Label resultMeta = new Label("Extracted message will appear here.");
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
        schemeBox = Forms.choice(operations.schemes(), StegoOperations.DEFAULT_SCHEME);
        cipherBox = Forms.choice(operations.ciphers(), StegoOperations.DEFAULT_CIPHER);
        build();
        disableProperty().bind(status.busyProperty());
    }

    private void build() {
        setPadding(new Insets(18));
        imageField.setPromptText("Stego image");
        extractButton.getStyleClass().add("primary");
        cipherBox.setDisable(true);
        saveButton.setDisable(true);
        result.setEditable(false);
        result.setWrapText(true);
        result.setPromptText("Extracted message");
        result.setPrefRowCount(8);
        resultMeta.getStyleClass().add("hint");
        resultMeta.setWrapText(true);
        result.setStyle("-fx-font-family: \"" + UiFonts.mono + "\";");

        VBox schemeField = Forms.field("Scheme", schemeBox);
        VBox cipherField = Forms.field("Cipher", cipherBox);
        HBox.setHgrow(schemeField, Priority.ALWAYS);
        HBox.setHgrow(cipherField, Priority.ALWAYS);
        schemeField.setMinWidth(0);
        cipherField.setMinWidth(0);
        VBox encryptKeyField = Forms.field("Encryption passphrase", encryptPassphrase);
        encryptKeyField.managedProperty().bind(decryptBox.selectedProperty());
        encryptKeyField.visibleProperty().bind(decryptBox.selectedProperty());
        VBox options = card("Keys", new VBox(10,
                Forms.field("Stego passphrase", stegoPassphrase),
                Forms.hint("Pixel-walk key. Blank matches an embed that left the stego passphrase empty."),
                decryptBox,
                encryptKeyField,
                schemeRow(schemeField, cipherField),
                Forms.hint("Turn decryption on when the hidden message was encrypted before embedding. "
                        + "The image itself is not ciphertext. The encryption passphrase is separate from "
                        + "the stego key and is never copied from it. Blank is the public empty-string AES-GCM key. "
                        + "Scheme and cipher match --scheme and --cipher.")));
        result.setMinWidth(0);
        result.setMaxHeight(Double.MAX_VALUE);
        VBox resultBody = new VBox(8, resultMeta, result, new HBox(8, saveButton));
        VBox.setVgrow(result, Priority.ALWAYS);
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
        decryptBox.selectedProperty().addListener((obs, previous, on) -> cipherBox.setDisable(!on));
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

    private static HBox schemeRow(VBox schemeField, VBox cipherField) {
        HBox row = new HBox(12, schemeField, cipherField);
        row.setMinWidth(0);
        return row;
    }

    void loadFromEmbed(Path stego, String stegoKey, String encryptKey, boolean decrypt, String scheme, String cipher) {
        imageField.setText(stego.toString());
        stegoPassphrase.setText(stegoKey);
        encryptPassphrase.setText(encryptKey);
        decryptBox.setSelected(decrypt);
        if (scheme != null && !scheme.isBlank()) {
            schemeBox.setValue(scheme);
        }
        if (cipher != null && !cipher.isBlank()) {
            cipherBox.setValue(cipher);
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
        boolean decrypt = decryptBox.isSelected();
        String encryptKey = decrypt ? encryptPassphrase.getText() : null;
        String scheme = schemeBox.getValue();
        String cipher = cipherBox.getValue();
        if (input == null) {
            status.show(StatusBar.Level.ERROR, "Choose a stego image.");
            return;
        }
        clearResult();
        status.run("Extracting…", new Task<byte[]>() {
            @Override
            protected byte[] call() throws Exception {
                BufferedImage image = StegoOperations.readImage(input);
                return operations.extract(image, stegoKey, decrypt, encryptKey, scheme, cipher);
            }
        }, bytes -> showPayload(bytes));
    }

    private void showPayload(byte[] bytes) {
        payload = bytes;
        PayloadPreview.View view = PayloadPreview.of(bytes);
        payloadIsText = view.text();
        result.setWrapText(view.text());
        result.setText(view.content());
        if (view.text() && view.content().isEmpty()) {
            resultMeta.setText("Recovered 0 bytes (empty message).");
        } else if (view.text()) {
            resultMeta.setText("Recovered " + bytes.length + " byte(s) of text.");
        } else {
            resultMeta.setText("Recovered " + bytes.length
                    + " binary byte(s). Save the file to keep the exact bytes.");
        }
        saveButton.setDisable(false);
        status.show(StatusBar.Level.SUCCESS, "Recovered " + bytes.length + " byte(s).");
    }

    private void save() {
        if (payload == null) {
            status.show(StatusBar.Level.ERROR, "Nothing to save yet.");
            return;
        }
        var chooser = Choosers.messageSave("Save extracted message", payloadIsText);
        Choosers.seed(chooser, null, payloadIsText ? "message.txt" : "message.bin");
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
        payload = null;
        payloadIsText = true;
        result.clear();
        resultMeta.setText("Extracted message will appear here.");
        saveButton.setDisable(true);
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
}
