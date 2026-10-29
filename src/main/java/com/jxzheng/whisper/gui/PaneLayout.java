package com.jxzheng.whisper.gui;

import javafx.geometry.Insets;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Shared resize behavior for Embed, Extract, and Analyze.
 *
 * <p>The preview sits beside the form while the window is wide enough, and
 * moves above the form when it is not. Two thresholds keep a drag across the
 * boundary from flipping layout on every pixel. Width and height are written
 * only when they move by at least one pixel, so a drag does not restyle the
 * scene on every event.
 */
final class PaneLayout {

    /** Collapse to a stacked preview below this width. */
    private static final double STACK_BELOW = 760;
    /** Stay stacked until the window is clearly wide enough to sit side by side. */
    private static final double UNSTACK_ABOVE = 840;
    private static final Insets STACKED_MARGIN = new Insets(0, 0, 16, 0);
    private static final Insets BESIDE_MARGIN = new Insets(0, 16, 0, 0);

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

        double column = Math.min(420, Math.max(260, width * 0.34));
        boolean layoutChanged = stack ? pane.getTop() != imageColumn : pane.getLeft() != imageColumn;
        if (layoutChanged) {
            if (stack) {
                pane.setLeft(null);
                pane.setTop(imageColumn);
                imageColumn.setPrefWidth(Region.USE_COMPUTED_SIZE);
                imageColumn.setMaxWidth(Double.MAX_VALUE);
                BorderPane.setMargin(imageColumn, STACKED_MARGIN);
            } else {
                pane.setTop(null);
                pane.setLeft(imageColumn);
                imageColumn.setPrefWidth(column);
                imageColumn.setMaxWidth(column);
                BorderPane.setMargin(imageColumn, BESIDE_MARGIN);
            }
            preview.adapt(stack, stack ? width : column);
        } else if (!stack && Math.abs(imageColumn.getPrefWidth() - column) >= 1) {
            imageColumn.setPrefWidth(column);
            imageColumn.setMaxWidth(column);
            preview.adapt(false, column);
        }
        if (pane.getCenter() != form) {
            pane.setCenter(form);
        }
    }
}
