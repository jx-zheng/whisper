package com.jxzheng.whisper.gui;

import com.jxzheng.whisper.ops.StegoOperations;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Desktop front-end for embed, extract, and analyze.
 * Launch with {@code mvn javafx:run} from the project directory.
 */
public final class WhisperApp extends Application {

    /** Matches {@code -whisper-bg} in whisper.css so the window frame is dark before CSS paints. */
    private static final Color WINDOW_FILL = Color.web("#101218");

    private final BorderPane root = new BorderPane();

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "Whisper");
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        UiFonts.load();
        StegoOperations operations = new StegoOperations();
        StatusBar status = new StatusBar();
        ExtractPane extract = new ExtractPane(operations, status);
        AnalyzePane analyze = new AnalyzePane(operations, status);

        ToggleGroup modes = new ToggleGroup();
        embedToggle = modeButton("Embed", modes);
        extractToggle = modeButton("Extract", modes);
        ToggleButton analyzeToggle = modeButton("Analyze", modes);
        EmbedPane embed = new EmbedPane(operations, status,
                (stego, stegoKey, encryptKey, decrypt, scheme, cipher) -> {
                    extract.loadFromEmbed(stego, stegoKey, encryptKey, decrypt, scheme, cipher);
                    extractToggle.setSelected(true);
                });
        embedToggle.setSelected(true);

        ScrollPane embedPage = scroll(embed);
        ScrollPane extractPage = scroll(extract);
        ScrollPane analyzePage = scroll(analyze);
        StackPane pages = new StackPane(embedPage, extractPage, analyzePage);
        modes.selectedToggleProperty().addListener((obs, previous, selected) -> {
            if (selected == null && previous != null) {
                modes.selectToggle(previous);
                return;
            }
            showPage(embedPage, selected == embedToggle);
            showPage(extractPage, selected == extractToggle);
            showPage(analyzePage, selected == analyzeToggle);
        });
        showPage(embedPage, true);
        showPage(extractPage, false);
        showPage(analyzePage, false);

        root.setStyle("-fx-font-family: \"" + UiFonts.sans + "\";");
        root.setTop(header(modes, embedToggle, extractToggle, analyzeToggle));
        root.setCenter(pages);
        root.setBottom(status);

        Scene scene = new Scene(root, PREFERRED_WIDTH, PREFERRED_HEIGHT, WINDOW_FILL);
        var stylesheet = WhisperApp.class.getResource("whisper.css");
        if (stylesheet == null) {
            throw new IllegalStateException("Missing whisper.css");
        }
        scene.getStylesheets().add(stylesheet.toExternalForm());
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN),
                () -> embedToggle.setSelected(true));
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.SHORTCUT_DOWN),
                () -> extractToggle.setSelected(true));
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.SHORTCUT_DOWN),
                () -> analyzeToggle.setSelected(true));

        stage.setTitle("Whisper");
        stage.setScene(scene);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        installIcons(stage);
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        stage.setWidth(Math.min(PREFERRED_WIDTH, Math.max(MIN_WIDTH, bounds.getWidth() - 48)));
        stage.setHeight(Math.min(PREFERRED_HEIGHT, Math.max(MIN_HEIGHT, bounds.getHeight() - 64)));
        stage.setX(bounds.getMinX() + Math.max(0, (bounds.getWidth() - stage.getWidth()) / 2));
        stage.setY(bounds.getMinY() + Math.max(0, (bounds.getHeight() - stage.getHeight()) / 2));
        stage.show();
    }

    private VBox header(ToggleGroup modes, ToggleButton embed, ToggleButton extract, ToggleButton analyze) {
        ImageView mark = new ImageView();
        var icon = WhisperApp.class.getResource("icon.png");
        if (icon != null) {
            // Decode above the CSS size so the mark stays sharp on high-DPI screens.
            mark.setImage(new Image(icon.toExternalForm(), 144, 144, true, true, false));
        }
        mark.setFitWidth(36);
        mark.setFitHeight(36);
        mark.setPreserveRatio(true);
        mark.setSmooth(true);
        Label title = new Label("whisper");
        title.getStyleClass().add("brand-title");
        Label subtitle = new Label("Adaptive steganography for images");
        subtitle.getStyleClass().add("hint");
        subtitle.setWrapText(true);
        subtitle.setMinWidth(0);
        VBox titles = new VBox(1, title, subtitle);
        titles.setMinWidth(0);
        HBox.setHgrow(titles, Priority.ALWAYS);
        subtitle.maxWidthProperty().bind(titles.widthProperty());
        HBox brand = new HBox(12, mark, titles);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.getStyleClass().add("header");
        HBox modesBar = new HBox(4, embed, extract, analyze);
        modesBar.getStyleClass().add("mode-bar");
        modesBar.setPadding(new Insets(0, 12, 0, 12));
        return new VBox(brand, modesBar);
    }

    /**
     * Largest first. JavaFX picks the closest size for the title bar, taskbar, and dock.
     * {@code icon.png} is the 512px master the header mark uses as well.
     */
    private static void installIcons(Stage stage) {
        for (String name : new String[] {
                "icon.png",
                "icon-256.png",
                "icon-128.png",
                "icon-64.png",
                "icon-48.png",
                "icon-32.png",
                "icon-16.png"}) {
            var url = WhisperApp.class.getResource(name);
            if (url != null) {
                stage.getIcons().add(new Image(url.toExternalForm(), false));
            }
        }
    }

    private static ToggleButton modeButton(String text, ToggleGroup group) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(group);
        button.getStyleClass().add("mode");
        button.setFocusTraversable(false);
        return button;
    }

    private static ScrollPane scroll(javafx.scene.layout.Region content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(false);
        scroll.getStyleClass().add("pane-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        content.setMinWidth(0);
        // At least as tall as the viewport so text areas can grow, and taller
        // than the viewport when the form needs a vertical scrollbar.
        scroll.viewportBoundsProperty().addListener((obs, previous, bounds) -> {
            double height = bounds.getHeight();
            if (height > 0 && Math.abs(content.getMinHeight() - height) > 1) {
                content.setMinHeight(height);
            }
        });
        return scroll;
    }

    private static void showPage(ScrollPane page, boolean show) {
        page.setVisible(show);
        page.setManaged(show);
    }

    private ToggleButton embedToggle;
    private ToggleButton extractToggle;

    /** Smallest window that still shows the tabs, a field, and a button. */
    private static final double MIN_WIDTH = 640;
    private static final double MIN_HEIGHT = 480;
    private static final double PREFERRED_WIDTH = 1080;
    private static final double PREFERRED_HEIGHT = 760;
}
