package com.jxzheng.whisper.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.FileChooser;
import javafx.stage.Window;

final class Choosers {

    private Choosers() {
    }

    static FileChooser images(String title) {
        FileChooser chooser = base(title);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(
                        "Images",
                        "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif",
                        "*.PNG", "*.JPG", "*.JPEG", "*.BMP", "*.GIF"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        return chooser;
    }

    static FileChooser stegoSave(String title) {
        FileChooser chooser = base(title);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("PNG image", "*.png"),
                new FileChooser.ExtensionFilter("BMP image", "*.bmp"));
        return chooser;
    }

    static FileChooser messageOpen(String title) {
        FileChooser chooser = base(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));
        return chooser;
    }

    static FileChooser messageSave(String title, boolean text) {
        FileChooser chooser = base(title);
        if (text) {
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Text file", "*.txt"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
        } else {
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Binary file", "*.bin"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
        }
        return chooser;
    }

    static void seed(FileChooser chooser, String currentPath, String fallbackFileName) {
        Path path = pathOrNull(currentPath);
        if (path != null) {
            Path parent = Files.isDirectory(path) ? path : path.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                chooser.setInitialDirectory(parent.toFile());
            }
            if (path.getFileName() != null && !Files.isDirectory(path)) {
                chooser.setInitialFileName(path.getFileName().toString());
                return;
            }
        }
        if (fallbackFileName != null && !fallbackFileName.isBlank()) {
            chooser.setInitialFileName(fallbackFileName);
        }
    }

    static Path withExtension(Path path, FileChooser chooser) {
        if (path == null || path.getFileName() == null) {
            return path;
        }
        String name = path.getFileName().toString();
        if (name.isBlank() || name.lastIndexOf('.') > 0) {
            return path;
        }
        FileChooser.ExtensionFilter filter = chooser.getSelectedExtensionFilter();
        if (filter == null || filter.getDescription().startsWith("All")) {
            return path;
        }
        String description = filter.getDescription().toLowerCase(Locale.ROOT);
        String extension = "png";
        if (description.contains("bmp")) {
            extension = "bmp";
        } else if (description.contains("text")) {
            extension = "txt";
        } else if (description.contains("binary")) {
            extension = "bin";
        }
        return path.resolveSibling(name + "." + extension);
    }

    static void remember(Path path) {
        if (path == null) {
            return;
        }
        Path directory = Files.isDirectory(path) ? path : path.getParent();
        UiPrefs.rememberDirectory(directory);
    }

    static Path chooseOpen(FileChooser chooser, Window owner) {
        var file = chooser.showOpenDialog(owner);
        if (file == null) {
            return null;
        }
        Path path = file.toPath();
        remember(path);
        return path;
    }

    static Path chooseSave(FileChooser chooser, Window owner) {
        var file = chooser.showSaveDialog(owner);
        if (file == null) {
            return null;
        }
        Path chosen = file.toPath();
        Path path = withExtension(chosen, chooser);
        if (path != null && !path.equals(chosen) && Files.exists(path) && !confirmOverwrite(path, owner)) {
            return null;
        }
        remember(path);
        return path;
    }

    static boolean confirmOverwrite(Path path, Window owner) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        if (owner != null) {
            alert.initOwner(owner);
        }
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        alert.setTitle("Overwrite file");
        alert.setHeaderText("Overwrite " + name + "?");
        alert.setContentText(path.toString());
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private static FileChooser base(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        Path directory = UiPrefs.lastDirectory();
        if (directory != null && Files.isDirectory(directory)) {
            chooser.setInitialDirectory(directory.toFile());
        }
        return chooser;
    }

    private static Path pathOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Path.of(text.trim());
    }
}
