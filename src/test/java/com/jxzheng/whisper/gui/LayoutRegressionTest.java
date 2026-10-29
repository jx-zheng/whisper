package com.jxzheng.whisper.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javafx.application.Platform;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;

/**
 * Headless check that the window can sit at the three review sizes without a
 * horizontal scrollbar. Run under xvfb: {@code -Dwhisper.layout=true}.
 */
class LayoutRegressionTest {

    @Test
    @EnabledIfSystemProperty(named = "whisper.layout", matches = "true")
    void threeSizesHaveNoHorizontalScroll() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Runnable body = () -> {
            try {
                Stage stage = new Stage();
                new WhisperApp().start(stage);
                for (double[] size : new double[][] {{780, 560}, {1080, 760}, {1600, 1000}}) {
                    stage.setWidth(size[0]);
                    stage.setHeight(size[1]);
                    var root = (javafx.scene.layout.Region) stage.getScene().getRoot();
                    root.resize(size[0], size[1]);
                    root.applyCss();
                    root.layout();
                    for (var node : root.lookupAll(".pane-scroll")) {
                        if (node instanceof ScrollPane scroll) {
                            assertEquals(ScrollPane.ScrollBarPolicy.NEVER, scroll.getHbarPolicy(),
                                    "horizontal scrollbar at " + size[0]);
                            scroll.resize(size[0], Math.max(200, size[1] - 140));
                            scroll.layout();
                            assertTrue(scroll.getWidth() <= size[0] + 1,
                                    "scroll " + scroll.getWidth() + " wider than " + size[0]);
                        }
                    }
                    for (var node : root.lookupAll(".image-view")) {
                        if (node instanceof javafx.scene.image.ImageView view && view.getFitWidth() > 0) {
                            assertTrue(view.getFitWidth() <= size[0],
                                    "thumbnail fitWidth " + view.getFitWidth());
                        }
                    }
                }
                String shots = System.getProperty("whisper.shots");
                if (shots != null && !shots.isBlank()) {
                    var rootNode = stage.getScene().getRoot();
                    var buttons = new java.util.ArrayList<javafx.scene.control.ToggleButton>();
                    for (var node : rootNode.lookupAll(".segment")) {
                        if (node instanceof javafx.scene.control.ToggleButton button) {
                            buttons.add(button);
                        }
                    }
                    java.nio.file.Path dir = java.nio.file.Path.of(shots);
                    java.nio.file.Files.createDirectories(dir);
                    String[] names = {"embed-text.png", "embed-file.png", "embed-image.png"};
                    for (int i = 0; i < Math.min(3, buttons.size()); i++) {
                        buttons.get(i).setSelected(true);
                        rootNode.applyCss();
                        rootNode.layout();
                        javafx.scene.image.WritableImage image = rootNode.snapshot(null, null);
                        int w = (int) image.getWidth();
                        int h = (int) image.getHeight();
                        java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                                w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        var reader = image.getPixelReader();
                        for (int y = 0; y < h; y++) {
                            for (int x = 0; x < w; x++) {
                                buffered.setRGB(x, y, reader.getArgb(x, y));
                            }
                        }
                        javax.imageio.ImageIO.write(buffered, "png", dir.resolve(names[i]).toFile());
                    }
                }
                stage.close();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        };
        try {
            Platform.startup(body);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(body);
        }
        assertTrue(latch.await(20, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }
}
