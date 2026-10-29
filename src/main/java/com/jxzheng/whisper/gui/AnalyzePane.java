package com.jxzheng.whisper.gui;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import com.jxzheng.whisper.analysis.ChiSquareAnalyzer;
import com.jxzheng.whisper.analysis.RsAnalyzer;
import com.jxzheng.whisper.ops.AnalysisFormat;
import com.jxzheng.whisper.ops.StegoOperations;
import com.jxzheng.whisper.ops.StegoOperations.Report;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

final class AnalyzePane extends BorderPane {

    /** Same threshold the CLI prints as {@code stego-like@0.95}. */
    private static final double ALPHA = 0.95;

    private final StegoOperations operations;
    private final StatusBar status;
    private final ImageCard imageCard = new ImageCard();
    private final TextField imageField = new TextField();
    private final Button analyzeButton = new Button("Run checks");
    private final Metric pValue = new Metric("p-value");
    private final Metric balance = new Metric("pair balance");
    private final Metric chiSquare = new Metric("χ²");
    private final Metric rate = new Metric("estimated LSB rate");
    private final Metric asymmetry = new Metric("asymmetry");
    private final Label badge = new Label("Not run");
    private final Label chiDetail = new Label("Degrees of freedom and sample count appear after a run.");
    private final Label rsDetail = new Label("Rm, Sm, R−m, and S−m appear after a run.");
    private final VBox imageColumn = new VBox(10);
    private final VBox form = new VBox(12);

    private int loadGeneration;
    private String loaded = "";
    private boolean arranged;
    private boolean stacked;

    AnalyzePane(StegoOperations operations, StatusBar status) {
        this.operations = operations;
        this.status = status;
        build();
        disableProperty().bind(status.busyProperty());
    }

    private void build() {
        setPadding(new Insets(18));
        imageField.setPromptText("Image to check");
        analyzeButton.getStyleClass().add("primary");
        badge.getStyleClass().addAll("badge", "badge-neutral");
        chiDetail.getStyleClass().add("hint");
        chiDetail.setWrapText(true);
        rsDetail.getStyleClass().add("hint");
        rsDetail.setWrapText(true);

        HBox chiMetrics = new HBox(18, pValue, balance, chiSquare);
        HBox rsMetrics = new HBox(18, rate, asymmetry);
        VBox chiCard = card("Chi-square", new VBox(8,
                badge,
                chiMetrics,
                chiDetail,
                Forms.hint("Westfeld–Pfitzmann: a high p-value means adjacent even/odd bins look equalized, "
                        + "which is what classic LSB replacement does. Zhang–Tang usually moves this less. "
                        + "Compare a cover with its stego — this is not a calibrated detector.")));
        VBox rsCard = card("RS analysis", new VBox(8,
                rsMetrics,
                rsDetail,
                Forms.hint("Fridrich RS estimates a classic-LSB embedding rate. n/a means the quadratic was "
                        + "unstable on this image. Asymmetry tends to grow under naive LSB.")));
        HBox actions = new HBox(8, analyzeButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        form.getChildren().addAll(actions, chiCard, rsCard);

        imageCard.attachPath("Image", imageField);
        imageColumn.getChildren().add(imageCard);
        Forms.wrapHints(form);
        Forms.wrapHints(imageColumn);
        imageCard.setOnBrowse(this::chooseImage);
        imageCard.setOnFile(this::useImage);
        imageField.setOnAction(event -> reload());
        imageField.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                reload();
            }
        });
        analyzeButton.setOnAction(event -> analyze());

        widthProperty().addListener((obs, previous, width) -> arrange(width.doubleValue()));
        placeImage(false);
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

    private void chooseImage() {
        var chooser = Choosers.images("Choose image to analyze");
        Choosers.seed(chooser, imageField.getText(), null);
        Path path = Choosers.chooseOpen(chooser, getScene() == null ? null : getScene().getWindow());
        if (path != null) {
            useImage(path);
        }
    }

    private void useImage(Path path) {
        imageField.setText(path.toString());
        loaded = "";
        reload();
    }

    private void reload() {
        Path path = pathFrom(imageField.getText());
        int token = ++loadGeneration;
        if (path == null) {
            loaded = "";
            imageCard.clear("No image selected");
            return;
        }
        if (path.toString().equals(loaded)) {
            return;
        }
        Background.start("whisper-analyze-preview", () -> {
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

    private void analyze() {
        Path input = pathFrom(imageField.getText());
        if (input == null) {
            status.show(StatusBar.Level.ERROR, "Choose an image to analyze.");
            return;
        }
        status.run("Analyzing…", new Task<AnalysisDone>() {
            @Override
            protected AnalysisDone call() throws Exception {
                BufferedImage image = StegoOperations.readImage(input);
                Report report = operations.analyze(image);
                return new AnalysisDone(
                        report,
                        FxImages.scale(image, FxImages.PREVIEW_EDGE),
                        image.getWidth(),
                        image.getHeight());
            }
        }, done -> {
            loadGeneration++;
            loaded = input.toString();
            imageCard.setPreview(FxImages.toFx(done.preview()));
            imageCard.setCaption(fileName(input));
            imageCard.setDetail(done.width() + "×" + done.height());
            imageCard.setWarning(null);
            apply(done);
            status.show(StatusBar.Level.SUCCESS, "Analysis complete.");
        });
    }

    private void apply(AnalysisDone done) {
        ChiSquareAnalyzer.Result chi = done.report().chiSquare();
        RsAnalyzer.Result rs = done.report().rs();
        pValue.setValue(AnalysisFormat.pValue(chi.pValue()));
        balance.setValue(AnalysisFormat.fixed(chi.pairBalance()));
        chiSquare.setValue(AnalysisFormat.fixed(chi.chiSquare()));
        rate.setValue(AnalysisFormat.rate(rs.estimatedRate()));
        asymmetry.setValue(AnalysisFormat.fixed(rs.maskAsymmetry()));
        chiDetail.setText("df " + chi.degreesOfFreedom()
                + " · " + AnalysisFormat.integer(chi.samples()) + " samples · "
                + done.width() + "×" + done.height());
        rsDetail.setText("Rm " + AnalysisFormat.fixed(rs.regularM())
                + "    Sm " + AnalysisFormat.fixed(rs.singularM())
                + "    R−m " + AnalysisFormat.fixed(rs.regularMinusM())
                + "    S−m " + AnalysisFormat.fixed(rs.singularMinusM()));
        boolean stegoLike = chi.stegoLike(ALPHA);
        badge.setText(stegoLike
                ? "Classic-LSB-like at p > 0.95"
                : "Not classic-LSB-like at p > 0.95");
        badge.getStyleClass().removeAll("badge-neutral", "badge-alert");
        badge.getStyleClass().add(stegoLike ? "badge-alert" : "badge-neutral");
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

    String pValueText() {
        return pValue.value();
    }

    String rateText() {
        return rate.value();
    }

    Button analyzeButton() {
        return analyzeButton;
    }

    TextField imageField() {
        return imageField;
    }

    private record AnalysisDone(Report report, BufferedImage preview, int width, int height) {
    }

    private record Loaded(BufferedImage preview, int width, int height) {
    }

    private static final class Metric extends VBox {
        private final Label value = new Label("—");

        Metric(String caption) {
            super(2);
            Label captionLabel = new Label(caption);
            captionLabel.getStyleClass().add("metric-caption");
            value.getStyleClass().add("metric-value");
            value.setStyle("-fx-font-family: \"" + UiFonts.mono + "\";");
            getChildren().addAll(value, captionLabel);
            setMinWidth(120);
        }

        void setValue(String text) {
            value.setText(text);
        }

        String value() {
            return value.getText();
        }
    }
}
