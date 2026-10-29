package com.jxzheng.whisper.gui;

import com.jxzheng.whisper.ops.StegoOperations;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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

    private final BorderPane root = new BorderPane();
    private Button themeButton;

    public static void main(String[] args) {
        String prism = System.getenv("WHISPER_PRISM");
        if (prism != null && !prism.isBlank()) {
            System.setProperty("prism.order", prism);
        }
        System.setProperty("apple.awt.application.name", "Whisper");
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        UiFonts.load();
        StegoOperations operations = new StegoOperations();
        StatusBar status = new StatusBar();
        EmbedPane embed = new EmbedPane(operations, status, (stego, key, decrypt, scheme, cipher) -> {
            extractHolder.load(stego, key, decrypt, scheme, cipher);
            showMode(extractToggle);
        });
        ExtractPane extract = new ExtractPane(operations, status);
        extractHolder.pane = extract;
        AnalyzePane analyze = new AnalyzePane(operations, status);

        ToggleGroup modes = new ToggleGroup();
        embedToggle = modeButton("Embed", modes);
        extractToggle = modeButton("Extract", modes);
        ToggleButton analyzeToggle = modeButton("Analyze", modes);
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

        boolean dark = UiPrefs.darkTheme();
        root.getStyleClass().add(dark ? "theme-dark" : "theme-light");
        root.setStyle("-fx-font-family: \"" + UiFonts.sans + "\";");
        root.setTop(header(dark, modes, embedToggle, extractToggle, analyzeToggle));
        root.setCenter(pages);
        root.setBottom(status);

        Scene scene = new Scene(root, 1080, 760);
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
        stage.setMinWidth(780);
        stage.setMinHeight(560);
        var icon = WhisperApp.class.getResource("icon.png");
        if (icon != null) {
            stage.getIcons().add(new Image(icon.toExternalForm()));
        }
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        stage.setWidth(Math.min(1080, Math.max(780, bounds.getWidth() - 48)));
        stage.setHeight(Math.min(800, Math.max(560, bounds.getHeight() - 64)));
        stage.setX(bounds.getMinX() + Math.max(0, (bounds.getWidth() - stage.getWidth()) / 2));
        stage.setY(bounds.getMinY() + Math.max(0, (bounds.getHeight() - stage.getHeight()) / 2));
        stage.show();

        SmokeRun.start(scene, status, themeButton, embedToggle, extractToggle, analyzeToggle, embed, extract, analyze);
    }

    private void showMode(ToggleButton button) {
        button.setSelected(true);
    }

    private VBox header(boolean dark, ToggleGroup modes, ToggleButton embed, ToggleButton extract, ToggleButton analyze) {
        ImageView mark = new ImageView();
        var icon = WhisperApp.class.getResource("icon.png");
        if (icon != null) {
            mark.setImage(new Image(icon.toExternalForm(), 32, 32, true, true));
        }
        mark.setFitWidth(32);
        mark.setFitHeight(32);
        Label title = new Label("whisper");
        title.getStyleClass().add("brand-title");
        Label subtitle = new Label("Zhang–Tang LSB-pair steganography");
        subtitle.getStyleClass().add("hint");
        VBox titles = new VBox(1, title, subtitle);
        themeButton = new Button(dark ? "Dark" : "Light");
        themeButton.setOnAction(event -> applyTheme(!root.getStyleClass().contains("theme-dark")));
        themeButton.setAccessibleText("Toggle color theme");
        HBox brand = new HBox(12, mark, titles, Forms.spacer(), themeButton);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.getStyleClass().add("header");
        HBox modesBar = new HBox(4, embed, extract, analyze);
        modesBar.getStyleClass().add("mode-bar");
        modesBar.setPadding(new Insets(0, 12, 0, 12));
        return new VBox(brand, modesBar);
    }

    private void applyTheme(boolean dark) {
        root.getStyleClass().removeAll("theme-light", "theme-dark");
        root.getStyleClass().add(dark ? "theme-dark" : "theme-light");
        themeButton.setText(dark ? "Dark" : "Light");
        UiPrefs.darkTheme(dark);
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
        HBox.setHgrow(content, Priority.ALWAYS);
        VBox.setVgrow(content, Priority.ALWAYS);
        return scroll;
    }

    private static void showPage(ScrollPane page, boolean show) {
        page.setVisible(show);
        page.setManaged(show);
    }

    private ToggleButton embedToggle;
    private ToggleButton extractToggle;
    private final ExtractRef extractHolder = new ExtractRef();

    private static final class ExtractRef {
        private ExtractPane pane;

        void load(java.nio.file.Path stego, String key, boolean decrypt, String scheme, String cipher) {
            pane.loadFromEmbed(stego, key, decrypt, scheme, cipher);
        }
    }
}
