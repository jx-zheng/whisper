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
                new FileChooser.ExtensionFilter("JPEG image", "*.jpg", "*.jpeg"),
                new FileChooser.ExtensionFilter("BMP image", "*.bmp"));
        return chooser;
    }

    static void selectStegoFilter(FileChooser chooser, String extension) {
        String want = switch (extension == null ? "" : extension.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "jpeg";
            case "bmp" -> "bmp";
            default -> "png";
        };
        for (FileChooser.ExtensionFilter filter : chooser.getExtensionFilters()) {
            if (filter.getDescription().toLowerCase(Locale.ROOT).contains(want)) {
                chooser.setSelectedExtensionFilter(filter);
                return;
            }
        }
    }

    static String saveExtension(String description) {
        if (description == null) {
            return "png";
        }
        String text = description.toLowerCase(Locale.ROOT);
        if (text.contains("bmp")) {
            return "bmp";
        }
        if (text.contains("jpeg") || text.contains("jpg")) {
            return "jpg";
        }
        if (text.contains("text")) {
            return "txt";
        }
        if (text.contains("binary")) {
            return "bin";
        }
        return "png";
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
        return path.resolveSibling(name + "." + saveExtension(filter.getDescription()));
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
        var css = Choosers.class.getResource("whisper.css");
        if (css != null) {
            alert.getDialogPane().getStylesheets().add(css.toExternalForm());
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
