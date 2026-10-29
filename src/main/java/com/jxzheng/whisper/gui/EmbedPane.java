package com.jxzheng.whisper.gui;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.jxzheng.whisper.encryption.AesCipher;
import com.jxzheng.whisper.media.ImageFormats;
import com.jxzheng.whisper.ops.AnalysisFormat;
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
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

final class EmbedPane extends BorderPane {

    private final StegoOperations operations;
    private final StatusBar status;
    private final StegoHandoff handoff;

    private final ImageCard imageCard = new ImageCard();
    private final TextField coverField = new TextField();
    private final TextArea messageArea = new TextArea();
    private final TextField messageFileField = new TextField();
    private final RadioButton textMode = new RadioButton("Text");
    private final RadioButton fileMode = new RadioButton("File");
    private final PassphraseField passphrase = new PassphraseField();
    private final CheckBox encryptBox = new CheckBox("Encrypt (AES-GCM)");
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
    private boolean arranged;
    private boolean stacked;

    private Path usedOutput;
    private String usedKey = "";
    private boolean usedEncrypt;
    private String usedScheme = StegoOperations.DEFAULT_SCHEME;
    private String usedCipher = StegoOperations.DEFAULT_CIPHER;

    EmbedPane(StegoOperations operations, StatusBar status, StegoHandoff handoff) {
        this.operations = operations;
        this.status = status;
        this.handoff = handoff;
        schemeBox = Forms.choice(operations.schemes(), StegoOperations.DEFAULT_SCHEME);
        cipherBox = Forms.choice(operations.ciphers(), StegoOperations.DEFAULT_CIPHER);
        build();
        disableProperty().bind(status.busyProperty());
    }

    private void build() {
        setPadding(new Insets(18));
        coverField.setPromptText("Cover image");
        messageArea.setPromptText("Text to hide");
        messageArea.setWrapText(true);
        messageArea.setPrefRowCount(4);
        messageFileField.setPromptText("Path to a UTF-8 text file");
        outputField.setPromptText("stego.png");
        embedButton.getStyleClass().add("primary");
        cipherBox.setDisable(true);
        openExtractButton.setVisible(false);
        openExtractButton.setManaged(false);

        ToggleGroup source = new ToggleGroup();
        textMode.setToggleGroup(source);
        fileMode.setToggleGroup(source);
        textMode.setSelected(true);
        VBox fileBox = new VBox(6,
                Forms.pathRow(messageFileField, browseButton("Browse", this::chooseMessageFile)),
                Forms.hint("Read as UTF-8 text, same as --message-file."));
        messageArea.managedProperty().bind(textMode.selectedProperty());
        messageArea.visibleProperty().bind(textMode.selectedProperty());
        fileBox.managedProperty().bind(fileMode.selectedProperty());
        fileBox.visibleProperty().bind(fileMode.selectedProperty());

        VBox messageCard = card("Message", new VBox(8,
                new HBox(16, textMode, fileMode),
                messageArea,
                fileBox));
        VBox schemeField = Forms.field("Scheme", schemeBox);
        VBox cipherField = Forms.field("Cipher", cipherBox);
        HBox.setHgrow(schemeField, Priority.ALWAYS);
        HBox.setHgrow(cipherField, Priority.ALWAYS);
        schemeField.setMaxWidth(Double.MAX_VALUE);
        cipherField.setMaxWidth(Double.MAX_VALUE);
        VBox optionsCard = card("Key and output", new VBox(10,
                Forms.field("Passphrase", passphrase),
                encryptBox,
                new HBox(12, schemeField, cipherField),
                Forms.field("Stego image", Forms.pathRow(outputField, browseButton("Save as…", this::chooseOutput))),
                Forms.hint("PNG or BMP only. The passphrase selects the pixel walk and, when encryption is on, "
                        + "AES-GCM (+" + AesCipher.OVERHEAD_BYTES + " bytes).")));

        sizeLabel.getStyleClass().add("hint");
        HBox actions = new HBox(8, embedButton, openExtractButton, Forms.spacer(), sizeLabel);
        actions.setAlignment(Pos.CENTER_LEFT);
        form.getChildren().addAll(messageCard, optionsCard, actions);
        VBox.setVgrow(messageArea, Priority.ALWAYS);

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
        passphrase.setOnAction(event -> embed());
        outputField.setOnAction(event -> embed());

        widthProperty().addListener((obs, previous, width) -> arrange(width.doubleValue()));
        placeImage(false);
        refreshSize();
    }

    private void arrange(double width) {
        if (width <= 0) {
            return;
        }
        boolean stack = width < 820;
        if (arranged && stack == stacked) {
            return;
        }
        arranged = true;
        stacked = stack;
        placeImage(stack);
    }

    private void placeImage(boolean stack) {
        if (stack) {
            setLeft(null);
            setTop(imageColumn);
            imageColumn.setPrefWidth(Region.USE_COMPUTED_SIZE);
            imageColumn.setMaxWidth(Double.MAX_VALUE);
            BorderPane.setMargin(imageColumn, new Insets(0, 0, 16, 0));
        } else {
            setTop(null);
            setLeft(imageColumn);
            imageColumn.setPrefWidth(340);
            imageColumn.setMaxWidth(340);
            BorderPane.setMargin(imageColumn, new Insets(0, 16, 0, 0));
        }
        setCenter(form);
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
            fileMode.setSelected(true);
        }
    }

    private void chooseOutput() {
        var chooser = Choosers.stegoSave("Save stego image");
        Choosers.seed(chooser, outputField.getText(), suggestionName(pathFrom(coverField.getText())));
        Path path = Choosers.chooseSave(chooser, getScene() == null ? null : getScene().getWindow());
        if (path != null) {
            outputField.setText(path.toString());
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
            BufferedImage image = StegoOperations.readImage(path);
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
            if (outputField.getText().isBlank()) {
                outputField.setText(path.resolveSibling(suggestionName(path)).toString());
            }
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
            coverPreview = null;
            imageCard.clear(fileName(path));
            imageCard.setWarning(error.getMessage());
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
        Path output = pathFrom(outputField.getText());
        String key = passphrase.getText();
        boolean encrypt = encryptBox.isSelected();
        String scheme = schemeBox.getValue();
        String cipher = cipherBox.getValue();
        boolean fromFile = fileMode.isSelected();
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
        if (key.isBlank()) {
            status.show(StatusBar.Level.ERROR, "Enter a passphrase.");
            return;
        }
        if (fromFile && messageFile == null) {
            status.show(StatusBar.Level.ERROR, "Choose a message file.");
            return;
        }

        openExtractButton.setVisible(false);
        openExtractButton.setManaged(false);
        status.run("Embedding…", new Task<EmbedDone>() {
            @Override
            protected EmbedDone call() throws Exception {
                BufferedImage image = StegoOperations.readImage(cover);
                byte[] message = fromFile
                        ? StegoOperations.readMessageFile(messageFile)
                        : messageText.getBytes(StandardCharsets.UTF_8);
                String warning = ImageFormats.lossyInputWarning(cover);
                EmbedResult result = operations.embed(image, message, key, encrypt, scheme, cipher);
                StegoOperations.writeStego(result.image(), output);
                return new EmbedDone(
                        FxImages.scale(image, FxImages.PREVIEW_EDGE),
                        FxImages.scale(result.image(), FxImages.PREVIEW_EDGE),
                        image.getWidth(),
                        image.getHeight(),
                        StegoOperations.maxPayloadBytes(image.getWidth(), image.getHeight()),
                        result.payloadBytes(),
                        warning);
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
            usedKey = key;
            usedEncrypt = encrypt;
            usedScheme = scheme == null ? StegoOperations.DEFAULT_SCHEME : scheme;
            usedCipher = cipher == null ? StegoOperations.DEFAULT_CIPHER : cipher;
            openExtractButton.setManaged(true);
            openExtractButton.setVisible(true);
            status.show(StatusBar.Level.SUCCESS,
                    "Embedded " + done.payloadBytes() + " byte(s) into " + output);
        });
    }

    private void openExtract() {
        if (usedOutput == null) {
            status.show(StatusBar.Level.ERROR, "Embed a message before opening Extract.");
            return;
        }
        handoff.openExtract(usedOutput, usedKey, usedEncrypt, usedScheme, usedCipher);
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
        if (fileMode.isSelected()) {
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

    private static VBox card(String title, javafx.scene.Node body) {
        Label heading = new Label(title);
        heading.getStyleClass().add("card-title");
        VBox box = new VBox(10, heading, body);
        box.getStyleClass().add("card");
        return box;
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

    Button embedButton() {
        return embedButton;
    }

    Button openExtractButton() {
        return openExtractButton;
    }

    TextField coverField() {
        return coverField;
    }

    TextArea messageArea() {
        return messageArea;
    }

    RadioButton textModeButton() {
        return textMode;
    }

    RadioButton fileModeButton() {
        return fileMode;
    }

    TextField messageFileField() {
        return messageFileField;
    }

    PassphraseField passphraseField() {
        return passphrase;
    }

    CheckBox encryptBox() {
        return encryptBox;
    }

    TextField outputField() {
        return outputField;
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
            String warning) {
    }
}
