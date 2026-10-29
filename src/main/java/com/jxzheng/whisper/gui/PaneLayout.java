package com.jxzheng.whisper.gui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

/**
 * Shared resize behavior for Embed, Extract, and Analyze.
 *
 * <p>The preview sits beside the form while the window is wide enough, and
 * moves above the form when it is not. Two thresholds keep a drag across the
 * boundary from flipping layout on every pixel.
 */
final class PaneLayout {

    /** Collapse to a stacked preview below this width. */
    private static final double STACK_BELOW = 760;
    /** Stay stacked until the window is clearly wide enough to sit side by side. */
    private static final double UNSTACK_ABOVE = 840;

    private PaneLayout() {
    }

    static boolean stacked(boolean currentlyStacked, double width) {
        return currentlyStacked ? width < UNSTACK_ABOVE : width < STACK_BELOW;
    }

    static void apply(BorderPane pane, VBox imageColumn, ImageCard preview, Region form, boolean stack, double width) {
        form.setMinWidth(0);
        form.setMaxWidth(Double.MAX_VALUE);
        imageColumn.setFillWidth(true);
        imageColumn.setMinWidth(0);
        imageColumn.setMaxHeight(Double.MAX_VALUE);
        clipToBounds(imageColumn);

        double column = Math.min(420, Math.max(260, width * 0.34));
        boolean layoutChanged = stack ? pane.getTop() != imageColumn : pane.getLeft() != imageColumn;
        if (stack) {
            if (layoutChanged) {
                pane.setLeft(null);
                pane.setTop(imageColumn);
            }
            imageColumn.setPrefWidth(Region.USE_COMPUTED_SIZE);
            imageColumn.setMaxWidth(Double.MAX_VALUE);
            BorderPane.setMargin(imageColumn, new Insets(0, 0, 16, 0));
        } else {
            if (layoutChanged) {
                pane.setTop(null);
                pane.setLeft(imageColumn);
            }
            imageColumn.setPrefWidth(column);
            imageColumn.setMaxWidth(column);
            BorderPane.setMargin(imageColumn, new Insets(0, 16, 0, 0));
        }
        if (pane.getCenter() != form) {
            pane.setCenter(form);
        }
        preview.adapt(stack, stack ? width : column);
        preview.setMaxWidth(stack ? Double.MAX_VALUE : column);
        if (layoutChanged && pane.getParent() instanceof ScrollPane scroll) {
            Platform.runLater(() -> scroll.setVvalue(0));
        }
    }

    /** Stops the preview card from painting over the form when its pref width is large. */
    private static void clipToBounds(VBox column) {
        if (column.getClip() instanceof Rectangle) {
            return;
        }
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(column.widthProperty());
        clip.heightProperty().bind(column.heightProperty());
        column.setClip(clip);
    }
}
