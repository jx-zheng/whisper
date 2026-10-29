package com.jxzheng.whisper.gui;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.jxzheng.whisper.carrier.CarrierCache;
import com.jxzheng.whisper.crypto.CipherRegistry;
import com.jxzheng.whisper.crypto.Envelope;
import com.jxzheng.whisper.crypto.KdfProfile;
import com.jxzheng.whisper.media.ImageFormats;
import com.jxzheng.whisper.payload.ImageFit;
import com.jxzheng.whisper.payload.PayloadKind;
import com.jxzheng.whisper.schemes.RatePreset;
import com.jxzheng.whisper.ops.AnalysisFormat;
import com.jxzheng.whisper.ops.PayloadOps;
import com.jxzheng.whisper.ops.StegoOperations;

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
    private final Label keyWarning = new Label(" ");
    private final PassphraseField stegoPassphrase = new PassphraseField("Optional — blank is not confidential");
    private final PassphraseField encryptPassphrase = new PassphraseField("Optional — blank is not confidential");
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
    private int coverWidth;
    private int coverHeight;
    private int coverNzac = -1;
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
    private String usedScheme = "auto";
    private String usedCipher = CipherRegistry.CHACHA;

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
        cipherBox.setMaxWidth(Double.MAX_VALUE);
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
            recomputeCapacity();
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
                "Separate encryption passphrase for the payload. Blank is not confidential. "
                        + "The picture itself is not encrypted.");
        encryptHint.managedProperty().bind(encryptBox.selectedProperty());
        encryptHint.visibleProperty().bind(encryptBox.selectedProperty());
        VBox optionsCard = card("Keys and output", new VBox(10,
                Forms.field("Stego passphrase", stegoPassphrase),
                Forms.hint("Stego passphrase. It also encrypts the payload unless you set a separate one. "
                        + "Blank is not confidential."),
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
        keyWarning.getStyleClass().add("badge-warn");
        keyWarning.setWrapText(true);
        keyWarning.setMaxWidth(Double.MAX_VALUE);
        keyWarning.setVisible(false);
        keyWarning.setManaged(false);
        form.getChildren().addAll(messageCard, optionsCard, keyWarning, actions, fingerprint);
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
        messageFileField.textProperty().addListener((obs, previous, next) -> {
            recomputeCapacity();
            refreshSize();
        });
        encryptBox.selectedProperty().addListener((obs, previous, on) -> {
            refreshSize();
            refreshKeyWarning();
        });
        cipherBox.valueProperty().addListener((obs, previous, value) -> refreshKeyWarning());
        schemeBox.valueProperty().addListener((obs, previous, value) -> {
            recomputeCapacity();
            suggestOutput(pathFrom(coverField.getText()));
        });
        presetBox.valueProperty().addListener((obs, previous, value) -> recomputeCapacity());
        stegoPassphrase.textProperty().addListener((obs, previous, value) -> refreshKeyWarning());
        encryptPassphrase.textProperty().addListener((obs, previous, value) -> refreshKeyWarning());
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
        refreshKeyWarning();
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
            coverWidth = 0;
            coverHeight = 0;
            coverNzac = -1;
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
            int nzac = -1;
            String ext = ImageFormats.extensionOf(path);
            if (ext.equals("jpg") || ext.equals("jpeg")) {
                try {
                    nzac = com.jxzheng.whisper.carrier.JpegCoefficients.parse(java.nio.file.Files.readAllBytes(path)).nzac();
                } catch (RuntimeException ignored) {
                    nzac = -1;
                }
            }
            String warning = ImageFormats.lossyInputWarning(path);
            if (nzac >= 0) {
                warning = null;
            }
            return new CoverLoad(
                    FxImages.scale(image, FxImages.PREVIEW_EDGE),
                    image.getWidth(),
                    image.getHeight(),
                    nzac,
                    warning);
        }, loaded -> {
            if (token != loadGeneration) {
                return;
            }
            loadedCover = path.toString();
            coverPreview = loaded.preview();
            stegoPreview = null;
            coverWidth = loaded.width();
            coverHeight = loaded.height();
            coverNzac = loaded.nzac();
            recomputeCapacity();
            viewCover.setSelected(true);
            updateViewToggle();
            showPreview();
            imageCard.setCaption(fileName(path));
            if (coverNzac < 0 && loaded.warning() != null) {
                imageCard.setWarning(loaded.warning());
            }
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
            coverWidth = 0;
            coverHeight = 0;
            coverNzac = -1;
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
        boolean text = textKind.isSelected();
        boolean image = imageKind.isSelected();
        boolean fit = fitBox.isSelected();
        String preset = presetBox.getValue();
        boolean fromFile = !text;
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
        int shownCapacity = capacity;
        status.run("Embedding…", new Task<EmbedDone>() {
            @Override
            protected EmbedDone call() throws Exception {
                return embedModern(new EmbedJob(
                        cover, output, stegoKey, encryptKey, encrypt, scheme, cipher, text, image, fit,
                        fromFile, messageText, messageFile, preset, shownCapacity));
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
            usedScheme = scheme == null || scheme.isBlank() ? "auto" : scheme;
            usedCipher = cipher == null || cipher.isBlank() ? CipherRegistry.CHACHA : cipher;
            openExtractButton.setManaged(true);
            openExtractButton.setVisible(true);
            fingerprint.setText(done.verified() == null ? "" : done.verified());
            status.show(StatusBar.Level.SUCCESS,
                    "Embedded " + done.payloadBytes() + " byte(s) into " + output
                            + (done.verified() == null ? "" : " · " + done.verified()));
        });
    }

    private EmbedDone embedModern(EmbedJob job) throws Exception {
        byte[] content;
        PayloadKind kind;
        String name = "";
        String mime = "text/plain";
        boolean reencoded = false;
        if (job.image()) {
            kind = PayloadKind.IMAGE;
            content = Files.readAllBytes(job.messageFile());
            name = job.messageFile().getFileName() == null ? "image" : job.messageFile().getFileName().toString();
            mime = "image/png";
            if (job.fit()) {
                ImageFit.Result fit = ImageFit.fit(content, 75, 1024);
                content = fit.bytes();
                reencoded = true;
                mime = "image/jpeg";
            }
        } else if (job.fromFile()) {
            kind = PayloadKind.FILE;
            content = Files.readAllBytes(job.messageFile());
            name = job.messageFile().getFileName() == null ? "file" : job.messageFile().getFileName().toString();
            mime = "application/octet-stream";
        } else {
            kind = PayloadKind.TEXT;
            content = job.messageText().getBytes(StandardCharsets.UTF_8);
        }
        String cipherId = CipherRegistry.shared().canonical(job.cipher());
        Envelope.SuiteKind suite = CipherRegistry.suite(cipherId);
        boolean separate = job.separate();
        var outcome = new PayloadOps().hide(new PayloadOps.HideRequest(
                job.cover(), job.output(), kind, content, name, mime, job.scheme(), suite,
                RatePreset.fromId(job.preset()), KdfProfile.DEFAULT,
                job.stegoKey().toCharArray(),
                separate && job.encryptKey() != null ? job.encryptKey().toCharArray() : null,
                separate && job.encryptKey() != null,
                false, reencoded));
        if (!outcome.ok()) {
            throw new IllegalArgumentException(outcome.message());
        }
        BufferedImage image = covers.load(job.cover());
        BufferedImage stego = null;
        try {
            stego = StegoOperations.readImage(job.output());
        } catch (Exception ignored) {
            stego = image;
        }
        return new EmbedDone(
                FxImages.scale(image, FxImages.PREVIEW_EDGE),
                FxImages.scale(stego, FxImages.PREVIEW_EDGE),
                image.getWidth(), image.getHeight(),
                job.shownCapacity(),
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
        String text = bytes + " B";
        if (capacity != Integer.MIN_VALUE && capacity >= 0) {
            text += " of about " + capacity + " B";
        }
        if (capacity != Integer.MIN_VALUE && (capacity < 0 || bytes > capacity)) {
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

    private String suggestionName(Path cover) {
        String ext = outputExtension(cover);
        if (cover == null || cover.getFileName() == null) {
            return "stego." + ext;
        }
        String name = cover.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + "-stego." + ext;
    }

    private String outputExtension(Path cover) {
        String scheme = schemeBox.getValue() == null ? "auto" : schemeBox.getValue();
        if (scheme.startsWith("uerd") || "jpeg".equals(scheme)) {
            return "jpg";
        }
        if (cover != null) {
            String ext = ImageFormats.extensionOf(cover);
            if ((ext.equals("jpg") || ext.equals("jpeg")) && "auto".equals(scheme)) {
                return "jpg";
            }
            if (ext.equals("bmp")) {
                return "bmp";
            }
        }
        return "png";
    }

    private void recomputeCapacity() {
        if (coverWidth <= 0 || coverHeight <= 0) {
            capacity = Integer.MIN_VALUE;
            refreshSize();
            return;
        }
        String scheme = schemeBox.getValue() == null ? "auto" : schemeBox.getValue();
        double rate = RatePreset.fromId(presetBox.getValue()).rate();
        int raw;
        if (coverNzac >= 0 && (scheme.startsWith("uerd") || "auto".equals(scheme) || "jpeg".equals(scheme))) {
            raw = com.jxzheng.whisper.schemes.framing.ParityFraming.userBudget(coverNzac, coverNzac, rate);
        } else {
            int pixels = coverWidth * coverHeight;
            raw = com.jxzheng.whisper.schemes.framing.ParityFraming.userBudget(
                    pixels, pixels, Math.min(rate, com.jxzheng.whisper.schemes.adaptive.HillStcScheme.HARD_RATE));
        }
        capacity = contentRoom(raw);
        if (coverPreview != null) {
            imageCard.setDetail(dimensions(coverWidth, coverHeight, capacity));
        }
        String warning = null;
        if (coverNzac >= 0 && scheme.startsWith("hill")) {
            warning = "HILL needs a PNG or BMP cover. JPEG covers use the JPEG scheme.";
        }
        imageCard.setWarning(warning);
        refreshSize();
    }

    private void refreshKeyWarning() {
        boolean separate = encryptBox.isSelected();
        boolean stegoBlank = stegoPassphrase.getText() == null || stegoPassphrase.getText().isEmpty();
        boolean encBlank = encryptPassphrase.getText() == null || encryptPassphrase.getText().isEmpty();
        boolean blank = separate ? encBlank : stegoBlank;
        keyWarning.setVisible(blank);
        keyWarning.setManaged(blank);
        keyWarning.setText(blank ? "Not confidential — the encryption passphrase is blank." : "");
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

    private int contentRoom(int userBytes) {
        String mime = textKind.isSelected() ? "text/plain"
                : imageKind.isSelected() ? "image/png" : "application/octet-stream";
        int name = 0;
        if (!textKind.isSelected()) {
            Path path = pathFrom(messageFileField.getText());
            if (path != null && path.getFileName() != null) {
                name = Math.min(255, path.getFileName().toString().getBytes(StandardCharsets.UTF_8).length);
            }
        }
        return Math.max(0, userBytes - (46 + name + mime.length()));
    }

    private record EmbedJob(
            Path cover,
            Path output,
            String stegoKey,
            String encryptKey,
            boolean separate,
            String scheme,
            String cipher,
            boolean text,
            boolean image,
            boolean fit,
            boolean fromFile,
            String messageText,
            Path messageFile,
            String preset,
            int shownCapacity) {
    }

    private record CoverLoad(BufferedImage preview, int width, int height, int nzac, String warning) {
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
