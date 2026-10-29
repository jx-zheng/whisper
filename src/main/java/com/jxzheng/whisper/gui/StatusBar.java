package com.jxzheng.whisper.gui;

import java.util.function.Consumer;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

final class StatusBar extends HBox {

    enum Level {
        INFO, SUCCESS, WARNING, ERROR
    }

    private final Region dot = new Region();
    private final Label message = new Label("Choose an image to begin.");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private Level level = Level.INFO;

    StatusBar() {
        super(10);
        getStyleClass().addAll("status-bar", "status-info");
        setAlignment(Pos.CENTER_LEFT);
        dot.getStyleClass().add("status-dot");
        message.setWrapText(true);
        message.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(message, Priority.ALWAYS);
        progress.setPrefSize(16, 16);
        progress.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        progress.setMaxSize(16, 16);
        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());
        getChildren().addAll(progress, dot, message);
    }

    boolean busy() {
        return busy.get();
    }

    ReadOnlyBooleanProperty busyProperty() {
        return busy;
    }

    Level level() {
        return level;
    }

    String message() {
        return message.getText();
    }

    void show(Level next, String text) {
        level = next;
        message.setText(text == null ? "" : text);
        progress.setVisible(false);
        applyLevel();
    }

    /**
     * Runs {@code task} off the JavaFX thread. {@code onSuccess} runs on the JavaFX thread
     * and must not call {@link #run} again.
     */
    <T> void run(String busyMessage, Task<T> task, Consumer<T> onSuccess) {
        if (busy.get()) {
            return;
        }
        busy.set(true);
        level = Level.INFO;
        message.setText(busyMessage);
        progress.setVisible(true);
        applyLevel();
        task.setOnSucceeded(event -> {
            try {
                onSuccess.accept(task.getValue());
            } catch (RuntimeException ex) {
                show(Level.ERROR, describe(ex));
            } finally {
                busy.set(false);
                progress.setVisible(false);
            }
        });
        task.setOnFailed(event -> {
            show(Level.ERROR, describe(task.getException()));
            busy.set(false);
        });
        Thread thread = new Thread(task, "whisper-task");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyLevel() {
        getStyleClass().removeAll("status-info", "status-success", "status-warning", "status-error");
        getStyleClass().add(switch (level) {
            case INFO -> "status-info";
            case SUCCESS -> "status-success";
            case WARNING -> "status-warning";
            case ERROR -> "status-error";
        });
    }

    private static String describe(Throwable error) {
        Throwable current = error;
        if (current instanceof java.util.concurrent.ExecutionException && current.getCause() != null) {
            current = current.getCause();
        }
        String text = current == null ? null : current.getMessage();
        if (text == null || text.isBlank()) {
            return "Something went wrong.";
        }
        return text;
    }
}
