package com.jxzheng.whisper.gui;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javafx.application.Platform;

/**
 * Daemon work that reports back on the JavaFX thread.
 * Preview loads use this so they do not take the busy lock used by embed, extract, and analyze.
 */
final class Background {

    private Background() {
    }

    static <T> void start(String name, Callable<T> work, Consumer<T> success, Consumer<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                T value = work.call();
                Platform.runLater(() -> success.accept(value));
            } catch (Exception ex) {
                Platform.runLater(() -> failure.accept(ex));
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }
}
