package com.jxzheng.whisper.gui;

import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

final class Forms {

    private Forms() {
    }

    static Label caption(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    static VBox field(String name, Node control) {
        return new VBox(6, caption(name), control);
    }

    static HBox pathRow(TextField field, Node browse) {
        field.setMinWidth(0);
        field.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(field, Priority.ALWAYS);
        if (browse instanceof Region region) {
            region.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox row = new HBox(8, field, browse);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(0);
        return row;
    }

    static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    static ComboBox<String> choice(List<String> items, String value) {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().setAll(items);
        box.setValue(value);
        box.setMinWidth(72);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("hint");
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setMaxWidth(280);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    /** Gives hint labels a real width so JavaFX wraps them instead of ellipsizing. */
    static void wrapHints(Region scope) {
        scope.widthProperty().addListener((obs, previous, width) -> {
            if (width.doubleValue() <= 0) {
                return;
            }
            // Card padding sits inside the form or image column, so wrap inside that.
            double max = Math.max(140, width.doubleValue() - 36);
            for (Node node : scope.lookupAll(".hint")) {
                if (node instanceof Label label) {
                    label.setWrapText(true);
                    label.setMinWidth(0);
                    label.setMaxWidth(max);
                }
            }
        });
    }
}
