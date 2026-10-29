package com.jxzheng.whisper.ops;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

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
            matchPermissions(tmp, destination);
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

    /**
     * Temp files are created mode 0600. On POSIX, take the replaced file's mode,
     * or the mode a new file would get under the current umask, before the move.
     */
    private static void matchPermissions(Path tmp, Path destination) throws IOException {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Set<PosixFilePermission> perms;
        try {
            if (Files.exists(destination)) {
                perms = Files.getPosixFilePermissions(destination);
            } else {
                perms = umaskPermissions(tmp.getParent() == null ? Path.of(".") : tmp.getParent());
                if (perms == null) {
                    return;
                }
            }
            Files.setPosixFilePermissions(tmp, perms);
        } catch (UnsupportedOperationException e) {
            return;
        }
    }

    private static Set<PosixFilePermission> umaskPermissions(Path dir) throws IOException {
        Path probe = Files.createTempFile(dir, ".whisper-perm-", ".tmp");
        try {
            Files.delete(probe);
            Files.createFile(probe);
            return Files.getPosixFilePermissions(probe);
        } catch (UnsupportedOperationException e) {
            return null;
        } finally {
            Files.deleteIfExists(probe);
        }
    }
}
