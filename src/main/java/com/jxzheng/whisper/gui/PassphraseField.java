package com.jxzheng.whisper.gui;

import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

final class PassphraseField extends HBox {

    private final PasswordField hidden = new PasswordField();
    private final TextField shown = new TextField();
    private final CheckBox show = new CheckBox("Show");

    PassphraseField(String prompt) {
        super(8);
        setAlignment(Pos.CENTER_LEFT);
        String text = prompt == null || prompt.isBlank() ? "Optional" : prompt;
        hidden.setPromptText(text);
        shown.setPromptText(text);
        hidden.setMinWidth(0);
        shown.setMinWidth(0);
        hidden.setPrefColumnCount(14);
        shown.setPrefColumnCount(14);
        hidden.setMaxWidth(Double.MAX_VALUE);
        shown.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(hidden, Priority.ALWAYS);
        HBox.setHgrow(shown, Priority.ALWAYS);
        setMinWidth(0);
        shown.textProperty().bindBidirectional(hidden.textProperty());
        shown.setVisible(false);
        shown.setManaged(false);
        show.selectedProperty().addListener((obs, was, on) -> {
            hidden.setVisible(!on);
            hidden.setManaged(!on);
            shown.setVisible(on);
            shown.setManaged(on);
            (on ? shown : hidden).requestFocus();
            (on ? shown : hidden).positionCaret((on ? shown : hidden).getText().length());
        });
        getChildren().addAll(hidden, shown, show);
    }

    String getText() {
        return hidden.getText() == null ? "" : hidden.getText();
    }

    void setText(String text) {
        hidden.setText(text == null ? "" : text);
    }

    void setOnAction(EventHandler<ActionEvent> handler) {
        hidden.setOnAction(handler);
        shown.setOnAction(handler);
    }
}
