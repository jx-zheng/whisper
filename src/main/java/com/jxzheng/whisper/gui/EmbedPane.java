package com.jxzheng.whisper.gui;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.jxzheng.whisper.carrier.CarrierCache;
import com.jxzheng.whisper.crypto.CipherRegistry;
import com.jxzheng.whisper.crypto.Envelope;
import com.jxzheng.whisper.crypto.KdfProfile;
import com.jxzheng.whisper.encryption.AesCipher;
import com.jxzheng.whisper.media.ImageFormats;
import com.jxzheng.whisper.payload.ImageFit;
import com.jxzheng.whisper.payload.PayloadKind;
import com.jxzheng.whisper.schemes.RatePreset;
import com.jxzheng.whisper.ops.AnalysisFormat;
import com.jxzheng.whisper.ops.PayloadOps;
import com.jxzheng.whisper.ops.StegoOperations;
import com.jxzheng.whisper.ops.StegoOperations.EmbedResult;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class EmbedPane extends BorderPane {

    private final StegoOperations operations;
    private final StatusBar status;
    private final Handoff handoff;
    private final CarrierCache covers = new CarrierCache();

    private final ImageCard imageCard = new ImageCard();
    private final TextField coverField = new TextField();
    private final TextArea messageArea = new TextArea();
    private final TextField messageFileField = new TextField();
    private final ToggleButton textKind = kindButton("Text", "Hide text");
    private final ToggleButton fileKind = kindButton("File", "Hide any file");
    private final ToggleButton imageKind = kindButton("Image", "Hide an image");
    private final CheckBox fitBox = new CheckBox("Fit to capacity");
    private final ComboBox<String> presetBox = Forms.choice(java.util.List.of("standard", "low", "paranoid"), "standard");
    private final Label fingerprint = new Label(" ");
    private final PassphraseField stegoPassphrase = new PassphraseField("Optional — blank uses the public walk");
    private final PassphraseField encryptPassphrase = new PassphraseField("Optional — blank uses the public cipher key");
    private final CheckBox encryptBox = new CheckBox("Separate encryption passphrase");
    private final ComboBox<String> schemeBox;
    private final ComboBox<String> cipherBox;
    private final TextField outputField = new TextField();
    private final Button embedButton = new Button("Embed message");
    private final Button openExtractButton = new Button("Open in Extract");
    private final Label sizeLabel = new Label("0 B");
    private final RadioButton viewCover = new RadioButton("Cover");
    private final RadioButton viewStego = new RadioButton("Stego");
    private final HBox viewToggle = new HBox(12, viewCover, viewStego);
    private final VBox imageColumn = new VBox(10);
    private final VBox form = new VBox(12);

    private int loadGeneration;
    private String loadedCover = "";
    private BufferedImage coverPreview;
    private BufferedImage stegoPreview;
    private int capacity = Integer.MIN_VALUE;
    private boolean stacked;

    /** Last output path this pane filled in from the cover name. A typed path is left alone. */
    private String suggestedOutput = "";
    /** Output path the save dialog already accepted, so embed does not ask again. */
    private String confirmedOutput = "";

    private Path usedOutput;
    private String usedStegoKey = "";
    private String usedEncryptKey = "";
    private boolean usedEncrypt;
    private String usedScheme = StegoOperations.DEFAULT_SCHEME;
    private String usedCipher = StegoOperations.DEFAULT_CIPHER;

    EmbedPane(StegoOperations operations, StatusBar status, Handoff handoff) {
        this.operations = operations;
        this.status = status;
        this.handoff = handoff;
        java.util.List<String> schemeNames = new java.util.ArrayList<>();
        schemeNames.add("auto");
        schemeNames.addAll(operations.schemes());
        schemeBox = Forms.choice(schemeNames, "auto");
        cipherBox = Forms.choice(operations.ciphers(), CipherRegistry.CHACHA);
        build();
        disableProperty().bind(status.busyProperty());
    }

    private void build() {
        setPadding(new Insets(18));
        coverField.setPromptText("Cover image");
        messageArea.setPromptText("Text to hide");
        messageArea.setWrapText(true);
        messageArea.setPrefRowCount(4);
        messageFileField.setPromptText("Drop any file, or type a path");
        outputField.setPromptText("stego.png");
        embedButton.getStyleClass().add("primary");
        cipherBox.setDisable(true);
        openExtractButton.setVisible(false);
        openExtractButton.setManaged(false);

        ToggleGroup kinds = new ToggleGroup();
        textKind.setToggleGroup(kinds);
        fileKind.setToggleGroup(kinds);
        imageKind.setToggleGroup(kinds);
        textKind.setSelected(true);
        HBox kindBar = new HBox(textKind, fileKind, imageKind);
        kindBar.getStyleClass().add("segmented");
        kindBar.setMaxWidth(Double.MAX_VALUE);
        VBox fileBox = new VBox(6,
                Forms.pathRow(messageFileField, browseButton("Browse", this::chooseMessageFile)),
                fitBox,
                Forms.hint("File keeps the bytes. Image shows a preview after extract. Fit re-encodes a smaller JPEG copy of a hidden image. The cover is not re-encoded."));
        fileBox.getStyleClass().add("drop-tile");
        imageKind.selectedProperty().addListener((obs, was, on) -> fileBox.getStyleClass().removeAll("image-tile", "drop-tile"));
        kinds.selectedToggleProperty().addListener((obs, was, selected) -> {
            fileBox.getStyleClass().removeAll("image-tile", "drop-tile");
            fileBox.getStyleClass().add(selected == imageKind ? "image-tile" : "drop-tile");
            messageFileField.setPromptText(selected == imageKind
                    ? "Image to hide (PNG, JPEG, GIF, BMP)"
                    : "Drop any file, or type a path");
            fitBox.setVisible(selected == imageKind);
            fitBox.setManaged(selected == imageKind);
        });
        fitBox.setVisible(false);
        fitBox.setManaged(false);
        messageArea.getStyleClass().add("paper");
        messageArea.managedProperty().bind(textKind.selectedProperty());
        messageArea.visibleProperty().bind(textKind.selectedProperty());
        fileBox.managedProperty().bind(textKind.selectedProperty().not());
        fileBox.visibleProperty().bind(textKind.selectedProperty().not());

        messageArea.setMinWidth(0);
        messageArea.setMaxHeight(Double.MAX_VALUE);
        VBox messageBody = new VBox(8, kindBar, messageArea, fileBox);
        VBox.setVgrow(messageArea, Priority.ALWAYS);
        VBox messageCard = card("Message", messageBody);
        VBox.setVgrow(messageBody, Priority.ALWAYS);
        VBox schemeField = Forms.field("Scheme", schemeBox);
        VBox cipherField = Forms.field("Cipher", cipherBox);
        HBox.setHgrow(schemeField, Priority.ALWAYS);
        HBox.setHgrow(cipherField, Priority.ALWAYS);
        schemeField.setMinWidth(0);
        cipherField.setMinWidth(0);
        schemeField.setMaxWidth(Double.MAX_VALUE);
        cipherField.setMaxWidth(Double.MAX_VALUE);
        VBox encryptKeyField = Forms.field("Encryption passphrase", encryptPassphrase);
        encryptKeyField.managedProperty().bind(encryptBox.selectedProperty());
        encryptKeyField.visibleProperty().bind(encryptBox.selectedProperty());
        Label encryptHint = Forms.hint(
                "AES-GCM for the payload only (+" + AesCipher.OVERHEAD_BYTES
                        + " bytes). Separate from the stego passphrase — it is never copied from that field. "
                        + "Blank is the public empty-string cipher key and is not confidential. "
                        + "The stego file stays a normal PNG or BMP.");
        encryptHint.managedProperty().bind(encryptBox.selectedProperty());
        encryptHint.visibleProperty().bind(encryptBox.selectedProperty());
        VBox optionsCard = card("Keys and output", new VBox(10,
                Forms.field("Stego passphrase", stegoPassphrase),
                Forms.hint("Pixel-walk key. Blank uses the public empty-string walk (PBKDF2 of \"\"). "
                        + "That walk is not a secret."),
                encryptBox,
                encryptKeyField,
                encryptHint,
                schemeRow(schemeField, cipherField),
                Forms.field("Stealth", presetBox),
                Forms.field("Stego image", Forms.pathRow(outputField, browseButton("Save as…", this::chooseOutput)))));

        sizeLabel.getStyleClass().add("hint");
        sizeLabel.setMinWidth(0);
        sizeLabel.setWrapText(true);
        FlowPane actions = new FlowPane(8, 8, embedButton, openExtractButton, sizeLabel);
        actions.setPrefWrapLength(420);
        fingerprint.getStyleClass().add("hint");
        fingerprint.setWrapText(true);
        fingerprint.setMaxWidth(Double.MAX_VALUE);
        form.getChildren().addAll(messageCard, optionsCard, actions, fingerprint);
        VBox.setVgrow(messageCard, Priority.ALWAYS);
        form.setMinWidth(0);

        ToggleGroup views = new ToggleGroup();
        viewCover.setToggleGroup(views);
        viewStego.setToggleGroup(views);
        viewCover.setSelected(true);
        viewToggle.setAlignment(Pos.CENTER_LEFT);
        viewToggle.setVisible(false);
        viewToggle.setManaged(false);
        imageCard.attachPath("Cover", coverField);
        imageCard.addExtra(viewToggle);
        imageColumn.getChildren().add(imageCard);
        VBox.setVgrow(imageCard, Priority.ALWAYS);
        imageCard.setMaxHeight(Double.MAX_VALUE);
        imageColumn.setFillWidth(true);
        Forms.wrapHints(form);
        Forms.wrapHints(imageColumn);

        imageCard.setOnBrowse(this::chooseCover);
        imageCard.setOnFile(this::useCover);
        coverField.setOnAction(event -> reloadCover());
        coverField.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                reloadCover();
            }
        });
        messageArea.textProperty().addListener((obs, previous, next) -> refreshSize());
        messageFileField.textProperty().addListener((obs, previous, next) -> refreshSize());
        encryptBox.selectedProperty().addListener((obs, previous, on) -> {
            cipherBox.setDisable(!on);
            refreshSize();
        });
        views.selectedToggleProperty().addListener((obs, previous, next) -> showPreview());
        embedButton.setOnAction(event -> embed());
        openExtractButton.setOnAction(event -> openExtract());
        stegoPassphrase.setOnAction(event -> embed());
        encryptPassphrase.setOnAction(event -> embed());
        outputField.setOnAction(event -> embed());

        widthProperty().addListener((obs, previous, width) -> arrange(width.doubleValue()));
        setMinWidth(0);
        PaneLayout.apply(this, imageColumn, imageCard, form, false, 1080);
        refreshSize();
    }

    private void arrange(double width) {
        if (width <= 0) {
            return;
        }
        stacked = PaneLayout.stacked(stacked, width);
        PaneLayout.apply(this, imageColumn, imageCard, form, stacked, width);
    }

    private void chooseCover() {
        var chooser = Choosers.images("Choose cover image");
        Choosers.seed(chooser, coverField.getText(), null);
        Path path = Choosers.chooseOpen(chooser, getScene() == null ? null : getScene().getWindow());
        if (path != null) {
            useCover(path);
        }
    }

    private void useCover(Path path) {
        coverField.setText(path.toString());
        loadedCover = "";
        reloadCover();
    }

    private void chooseMessageFile() {
        var chooser = Choosers.messageOpen("Choose message file");
        Choosers.seed(chooser, messageFileField.getText(), null);
        Path path = Choosers.chooseOpen(chooser, getScene() == null ? null : getScene().getWindow());
        if (path != null) {
            messageFileField.setText(path.toString());
            if (textKind.isSelected()) {
                fileKind.setSelected(true);
            }
        }
    }

    private void chooseOutput() {
        var chooser = Choosers.stegoSave("Save stego image");
        Choosers.seed(chooser, outputField.getText(), suggestionName(pathFrom(coverField.getText())));
        Path path = Choosers.chooseSave(chooser, getScene() == null ? null : getScene().getWindow());
        if (path != null) {
            outputField.setText(path.toString());
            confirmedOutput = path.toString();
        }
    }

    private void reloadCover() {
        Path path = pathFrom(coverField.getText());
        int token = ++loadGeneration;
        if (path == null) {
            loadedCover = "";
            capacity = Integer.MIN_VALUE;
            coverPreview = null;
            stegoPreview = null;
            updateViewToggle();
            imageCard.clear("No image selected");
            refreshSize();
            return;
        }
        if (path.toString().equals(loadedCover) && coverPreview != null) {
            return;
        }
        Background.start("whisper-cover", () -> {
            BufferedImage image = covers.load(path);
            return new CoverLoad(
                    FxImages.scale(image, FxImages.PREVIEW_EDGE),
                    image.getWidth(),
                    image.getHeight(),
                    StegoOperations.maxPayloadBytes(image.getWidth(), image.getHeight()),
                    ImageFormats.lossyInputWarning(path));
        }, loaded -> {
            if (token != loadGeneration) {
                return;
            }
            loadedCover = path.toString();
            coverPreview = loaded.preview();
            stegoPreview = null;
            capacity = loaded.capacity();
            viewCover.setSelected(true);
            updateViewToggle();
            showPreview();
            imageCard.setCaption(fileName(path));
            imageCard.setDetail(dimensions(loaded.width(), loaded.height(), loaded.capacity()));
            imageCard.setWarning(loaded.warning());
            suggestOutput(path);
            refreshSize();
            if (loaded.warning() != null) {
                status.show(StatusBar.Level.WARNING, loaded.warning());
            } else if (status.level() != StatusBar.Level.SUCCESS) {
                status.show(StatusBar.Level.SUCCESS, "Cover loaded.");
            }
        }, error -> {
            if (token != loadGeneration) {
                return;
            }
            loadedCover = "";
            capacity = Integer.MIN_VALUE;
            coverPreview = null;
            stegoPreview = null;
            updateViewToggle();
            imageCard.clear(fileName(path));
            imageCard.setWarning(error.getMessage());
            refreshSize();
            status.show(StatusBar.Level.ERROR, error.getMessage());
        });
    }

    private void showPreview() {
        BufferedImage image = viewStego.isSelected() && stegoPreview != null ? stegoPreview : coverPreview;
        imageCard.setPreview(image == null ? null : FxImages.toFx(image));
    }

    private void updateViewToggle() {
        boolean hasStego = stegoPreview != null;
        viewToggle.setVisible(hasStego);
        viewToggle.setManaged(hasStego);
    }

    private void embed() {
        Path cover = pathFrom(coverField.getText());
        if (cover != null) {
            String current = outputField.getText() == null ? "" : outputField.getText().trim();
            if (current.equals(suggestedOutput) && !cover.toString().equals(loadedCover)) {
                suggestOutput(cover);
            }
        }
        Path output = pathFrom(outputField.getText());
        String stegoKey = stegoPassphrase.getText();
        boolean encrypt = encryptBox.isSelected();
        String encryptKey = encrypt ? encryptPassphrase.getText() : null;
        String scheme = schemeBox.getValue();
        String cipher = cipherBox.getValue();
        boolean fromFile = !textKind.isSelected();
        String messageText = messageArea.getText() == null ? "" : messageArea.getText();
        Path messageFile = pathFrom(messageFileField.getText());

        if (cover == null) {
            status.show(StatusBar.Level.ERROR, "Choose a cover image.");
            return;
        }
        if (output == null) {
            status.show(StatusBar.Level.ERROR, "Choose where to save the stego image.");
            return;
        }
        if (fromFile && messageFile == null) {
            status.show(StatusBar.Level.ERROR, "Choose a message file.");
            return;
        }
        if (samePath(cover, output)) {
            status.show(StatusBar.Level.ERROR, "Stego output must be a different file from the cover.");
            return;
        }
        boolean justConfirmed = output.toString().equals(confirmedOutput);
        if (Files.isRegularFile(output) && !justConfirmed
                && !Choosers.confirmOverwrite(output, getScene() == null ? null : getScene().getWindow())) {
            return;
        }
        if (justConfirmed) {
            confirmedOutput = "";
        }

        openExtractButton.setVisible(false);
        openExtractButton.setManaged(false);
        status.run("Embedding…", new Task<EmbedDone>() {
            @Override
            protected EmbedDone call() throws Exception {
                if (modernEmbed(scheme, cipher, encrypt)) {
                    return embedModern(cover, output, stegoKey, encryptKey, scheme, cipher, fromFile, messageText, messageFile);
                }
                BufferedImage image = covers.load(cover);
                byte[] message = fromFile
                        ? StegoOperations.readMessageFile(messageFile)
                        : messageText.getBytes(StandardCharsets.UTF_8);
                String warning = ImageFormats.lossyInputWarning(cover);
                EmbedResult result = operations.embed(image, message, stegoKey, encrypt, encryptKey, scheme, cipher);
                StegoOperations.writeStego(result.image(), output);
                return new EmbedDone(
                        FxImages.scale(image, FxImages.PREVIEW_EDGE),
                        FxImages.scale(result.image(), FxImages.PREVIEW_EDGE),
                        image.getWidth(),
                        image.getHeight(),
                        StegoOperations.maxPayloadBytes(image.getWidth(), image.getHeight()),
                        result.payloadBytes(),
                        warning,
                        null);
            }
        }, done -> {
            loadGeneration++;
            loadedCover = cover.toString();
            coverPreview = done.coverPreview();
            stegoPreview = done.stegoPreview();
            capacity = done.capacity();
            viewStego.setSelected(true);
            updateViewToggle();
            showPreview();
            imageCard.setCaption("Stego preview");
            imageCard.setDetail(fileName(output) + " · " + dimensions(done.width(), done.height(), done.capacity()));
            imageCard.setWarning(done.warning());
            usedOutput = output;
            usedStegoKey = stegoKey;
            usedEncryptKey = encrypt ? encryptKey : "";
            usedEncrypt = encrypt;
            usedScheme = scheme == null ? StegoOperations.DEFAULT_SCHEME : scheme;
            usedCipher = cipher == null ? StegoOperations.DEFAULT_CIPHER : cipher;
            openExtractButton.setManaged(true);
            openExtractButton.setVisible(true);
            fingerprint.setText(done.verified() == null ? "" : done.verified());
            status.show(StatusBar.Level.SUCCESS,
                    "Embedded " + done.payloadBytes() + " byte(s) into " + output
                            + (done.verified() == null ? "" : " · " + done.verified()));
        });
    }

    private static boolean modernEmbed(String scheme, String cipher, boolean encrypt) {
        if (scheme != null && (scheme.equals("auto") || scheme.startsWith("hill") || scheme.startsWith("uerd"))) {
            return true;
        }
        if (!encrypt) {
            return false;
        }
        return cipher != null && !cipher.equals("aes");
    }

    private EmbedDone embedModern(
            Path cover, Path output, String stegoKey, String encryptKey, String scheme, String cipher,
            boolean fromFile, String messageText, Path messageFile) throws Exception {
        byte[] content;
        PayloadKind kind;
        String name = "";
        String mime = "text/plain";
        boolean reencoded = false;
        if (imageKind.isSelected()) {
            kind = PayloadKind.IMAGE;
            content = Files.readAllBytes(messageFile);
            name = messageFile.getFileName() == null ? "image" : messageFile.getFileName().toString();
            mime = "image/png";
            if (fitBox.isSelected()) {
                ImageFit.Result fit = ImageFit.fit(content, 75, 1024);
                content = fit.bytes();
                reencoded = true;
                mime = "image/jpeg";
            }
        } else if (fromFile) {
            kind = PayloadKind.FILE;
            content = Files.readAllBytes(messageFile);
            name = messageFile.getFileName() == null ? "file" : messageFile.getFileName().toString();
            mime = "application/octet-stream";
        } else {
            kind = PayloadKind.TEXT;
            content = messageText.getBytes(StandardCharsets.UTF_8);
        }
        boolean encrypting = !"none".equals(cipher);
        Envelope.SuiteKind suite = switch (cipher == null ? "" : cipher) {
            case "aes-256-gcm" -> Envelope.SuiteKind.AES;
            case "none" -> Envelope.SuiteKind.INTEGRITY;
            default -> Envelope.SuiteKind.CHACHA;
        };
        var outcome = new PayloadOps().hide(new PayloadOps.HideRequest(
                cover, output, kind, content, name, mime, scheme, suite,
                RatePreset.fromId(presetBox.getValue()), KdfProfile.DEFAULT,
                stegoKey.toCharArray(),
                encrypting && encryptKey != null ? encryptKey.toCharArray() : null,
                encrypting && encryptKey != null,
                false, reencoded));
        if (!outcome.ok()) {
            throw new IllegalArgumentException(outcome.message());
        }
        BufferedImage image = covers.load(cover);
        BufferedImage stego = null;
        try {
            stego = StegoOperations.readImage(output);
        } catch (Exception ignored) {
            stego = image;
        }
        return new EmbedDone(
                FxImages.scale(image, FxImages.PREVIEW_EDGE),
                FxImages.scale(stego, FxImages.PREVIEW_EDGE),
                image.getWidth(), image.getHeight(),
                StegoOperations.maxPayloadBytes(image.getWidth(), image.getHeight()),
                content.length, null, outcome.message());
    }

    private void openExtract() {
        if (usedOutput == null) {
            status.show(StatusBar.Level.ERROR, "Embed a message before opening Extract.");
            return;
        }
        handoff.openExtract(usedOutput, usedStegoKey, usedEncryptKey, usedEncrypt, usedScheme, usedCipher);
    }

    private void refreshSize() {
        int bytes = plaintextBytes();
        sizeLabel.getStyleClass().remove("warn");
        if (bytes < 0) {
            sizeLabel.setText("No message file");
            return;
        }
        boolean encrypt = encryptBox.isSelected();
        int packaged = StegoOperations.packagedLength(bytes, encrypt);
        String text = encrypt ? bytes + " B · " + packaged + " B embedded" : bytes + " B";
        if (capacity != Integer.MIN_VALUE && (capacity < 0 || packaged > capacity)) {
            text += " · too large";
            sizeLabel.getStyleClass().add("warn");
        }
        sizeLabel.setText(text);
    }

    private int plaintextBytes() {
        if (!textKind.isSelected()) {
            Path path = pathFrom(messageFileField.getText());
            if (path == null || !Files.isRegularFile(path)) {
                return -1;
            }
            try {
                return Math.toIntExact(Files.size(path));
            } catch (Exception ex) {
                return Integer.MAX_VALUE;
            }
        }
        String text = messageArea.getText();
        return (text == null ? "" : text).getBytes(StandardCharsets.UTF_8).length;
    }

    private static HBox schemeRow(VBox schemeField, VBox cipherField) {
        HBox row = new HBox(12, schemeField, cipherField);
        row.setMinWidth(0);
        return row;
    }

    private static VBox card(String title, javafx.scene.Node body) {
        Label heading = new Label(title);
        heading.getStyleClass().add("card-title");
        VBox box = new VBox(10, heading, body);
        box.getStyleClass().add("card");
        return box;
    }

    private static ToggleButton kindButton(String text, String accessible) {
        ToggleButton button = new ToggleButton(text);
        button.getStyleClass().add("segment");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAccessibleText(accessible);
        return button;
    }

    private static Button browseButton(String text, Runnable action) {
        Button button = new Button(text);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static String dimensions(int width, int height, int capacity) {
        if (capacity < 0) {
            return width + "×" + height + " · too small to embed";
        }
        return width + "×" + height + " · holds " + AnalysisFormat.integer(capacity) + " bytes";
    }

    private void suggestOutput(Path cover) {
        String suggestion = cover.resolveSibling(suggestionName(cover)).toString();
        String current = outputField.getText() == null ? "" : outputField.getText().trim();
        if (current.isEmpty() || current.equals(suggestedOutput)) {
            outputField.setText(suggestion);
            suggestedOutput = suggestion;
        }
    }

    private static boolean samePath(Path left, Path right) {
        Path a = left.toAbsolutePath().normalize();
        Path b = right.toAbsolutePath().normalize();
        if (a.equals(b)) {
            return true;
        }
        try {
            return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
        } catch (java.io.IOException ex) {
            return false;
        }
    }

    private static String suggestionName(Path cover) {
        if (cover == null || cover.getFileName() == null) {
            return "stego.png";
        }
        String name = cover.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + "-stego.png";
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

    @FunctionalInterface
    interface Handoff {
        void openExtract(Path stego, String stegoKey, String encryptKey, boolean decrypt, String scheme, String cipher);
    }

    private record CoverLoad(BufferedImage preview, int width, int height, int capacity, String warning) {
    }

    private record EmbedDone(
            BufferedImage coverPreview,
            BufferedImage stegoPreview,
            int width,
            int height,
            int capacity,
            int payloadBytes,
            String warning,
            String verified) {
    }
}
