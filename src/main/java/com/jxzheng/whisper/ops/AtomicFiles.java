package com.jxzheng.whisper.ops;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writes a finished stego file by moving a temp file in the same directory. */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void write(Path destination, byte[] bytes) throws IOException {
        if (destination == null) {
            throw new IOException("Output path is missing");
        }
        Path parent = destination.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            throw new IOException("Output directory does not exist: " + parent);
        }
        Path dir = parent == null ? Path.of(".") : parent;
        Path tmp = Files.createTempFile(dir, ".whisper-", ".part");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
