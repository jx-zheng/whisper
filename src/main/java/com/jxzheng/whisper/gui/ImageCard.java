package com.jxzheng.whisper.gui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.DragEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

final class ImageCard extends VBox {

    private final StackPane frame = new StackPane();
    private final ImageView imageView = new ImageView();
    private final Label placeholder = new Label("Drop an image here, or click to browse");
    private final Label caption = new Label("No image selected");
    private final Label detail = new Label(" ");
    private final Label warning = new Label();
    private final Button browse = new Button("Browse");
    private Consumer<Path> onFile = path -> {
    };
    private Runnable onBrowse = () -> {
    };
    private boolean adapted;
    private boolean adaptedStacked;

    ImageCard() {
        super(8);
        getStyleClass().add("card");
        frame.getStyleClass().add("preview-frame");
        frame.setMinWidth(0);
        frame.setMinHeight(140);
        frame.setPrefHeight(200);
        frame.setMaxWidth(Double.MAX_VALUE);
        setMinWidth(0);
        frame.setAlignment(Pos.CENTER);
        frame.setCursor(Cursor.HAND);
        imageView.setPreserveRatio(true);
        imageView.setFitHeight(180);
        imageView.setFitWidth(280);
        imageView.setSmooth(true);
        imageView.setMouseTransparent(true);
        frame.widthProperty().addListener((obs, previous, width) -> fitPreview(width.doubleValue(), frame.getHeight()));
        frame.heightProperty().addListener((obs, previous, height) -> fitPreview(frame.getWidth(), height.doubleValue()));
        placeholder.getStyleClass().add("hint");
        placeholder.setWrapText(true);
        placeholder.setMouseTransparent(true);
        placeholder.setTextAlignment(TextAlignment.CENTER);
        placeholder.setAlignment(Pos.CENTER);
        placeholder.setMinWidth(0);
        placeholder.setMaxWidth(220);
        frame.getChildren().addAll(imageView, placeholder);

        caption.getStyleClass().add("card-title");
        caption.setWrapText(true);
        detail.getStyleClass().add("hint");
        detail.setWrapText(true);
        warning.getStyleClass().add("warn");
        warning.setWrapText(true);
        warning.setVisible(false);
        warning.setManaged(false);
        warning.maxWidthProperty().bind(widthProperty());

        browse.setMinWidth(Region.USE_PREF_SIZE);
        browse.setOnAction(event -> onBrowse.run());
        frame.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                onBrowse.run();
                event.consume();
            }
        });
        frame.setOnDragOver(this::dragOver);
        frame.setOnDragExited(event -> {
            frame.getStyleClass().remove("drag-over");
            event.consume();
        });
        frame.setOnDragDropped(this::dropped);

        getChildren().addAll(frame, caption, detail, warning, browse);
    }

    /**
     * Stacked layout keeps a short preview so the form stays on screen.
     * Side by side, the preview grows with the column and with leftover height.
     * Callers should skip this unless the mode flipped or the column width moved by a pixel.
     */
    void adapt(boolean stacked, double columnWidth) {
        if (!adapted || adaptedStacked != stacked) {
            adapted = true;
            adaptedStacked = stacked;
            if (stacked) {
                VBox.setVgrow(frame, Priority.NEVER);
                frame.setMinHeight(120);
                frame.setPrefHeight(168);
                frame.setMaxHeight(168);
            } else {
                VBox.setVgrow(frame, Priority.ALWAYS);
                frame.setMaxHeight(Double.MAX_VALUE);
                frame.setMinHeight(160);
                applySideHeight(columnWidth);
            }
            return;
        }
        if (!stacked) {
            applySideHeight(columnWidth);
        }
    }

    private void applySideHeight(double columnWidth) {
        double preferred = columnWidth > 0 ? columnWidth * 0.72 : 220;
        double height = Math.min(340, Math.max(200, preferred));
        if (Math.abs(frame.getPrefHeight() - height) >= 1) {
            frame.setPrefHeight(height);
        }
    }

    private void fitPreview(double width, double height) {
        if (width > 48) {
            double inner = width - 24;
            imageView.setFitWidth(inner);
            placeholder.setMaxWidth(Math.max(80, inner));
        }
        if (height > 32) {
            imageView.setFitHeight(height - 16);
        }
    }

    void setOnBrowse(Runnable onBrowse) {
        this.onBrowse = onBrowse == null ? () -> {
        } : onBrowse;
    }

    void setOnFile(Consumer<Path> onFile) {
        this.onFile = onFile == null ? path -> {
        } : onFile;
    }

    void setPreview(Image image) {
        imageView.setImage(image);
        boolean empty = image == null;
        placeholder.setVisible(empty);
        placeholder.setManaged(empty);
    }

    void setCaption(String text) {
        caption.setText(text == null || text.isBlank() ? "No image selected" : text);
    }

    void setDetail(String text) {
        detail.setText(text == null || text.isBlank() ? " " : text);
    }

    void setWarning(String text) {
        boolean show = text != null && !text.isBlank();
        warning.setText(show ? text : "");
        warning.setVisible(show);
        warning.setManaged(show);
    }

    /**
     * Shows a labeled, editable path beside the browse button. Clicking the preview still browses.
     */
    void attachPath(String labelText, javafx.scene.control.TextField field) {
        field.setMinWidth(0);
        field.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(field, Priority.ALWAYS);
        HBox row = new HBox(8, field, browse);
        row.setAlignment(Pos.CENTER_LEFT);
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        VBox block = new VBox(4, label, row);
        int index = getChildren().indexOf(browse);
        if (index >= 0) {
            getChildren().set(index, block);
        } else {
            getChildren().add(block);
        }
    }

    void addExtra(Node node) {
        getChildren().add(node);
    }

    void clear(String captionText) {
        setPreview(null);
        setCaption(captionText);
        setDetail(" ");
        setWarning(null);
    }

    private void dragOver(DragEvent event) {
        if (event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
            if (!frame.getStyleClass().contains("drag-over")) {
                frame.getStyleClass().add("drag-over");
            }
        }
        event.consume();
    }

    private void dropped(DragEvent event) {
        frame.getStyleClass().remove("drag-over");
        List<java.io.File> files = event.getDragboard().getFiles();
        boolean accepted = files != null && !files.isEmpty();
        if (accepted) {
            onFile.accept(files.get(0).toPath());
        }
        event.setDropCompleted(accepted);
        event.consume();
    }
}
