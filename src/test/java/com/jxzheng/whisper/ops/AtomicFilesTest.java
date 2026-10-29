package com.jxzheng.whisper.ops;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {

    @Test
    void newFileUsesUmaskAndReplaceKeepsMode(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path probe = dir.resolve("umask-probe");
        Files.createFile(probe);
        Set<PosixFilePermission> umask = Files.getPosixFilePermissions(probe);

        Path created = dir.resolve("stego.png");
        AtomicFiles.write(created, new byte[] {1, 2, 3});
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(created));
        assertEquals(umask, Files.getPosixFilePermissions(created));

        Set<PosixFilePermission> restricted = PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(created, restricted);
        AtomicFiles.write(created, new byte[] {4, 5});
        assertArrayEquals(new byte[] {4, 5}, Files.readAllBytes(created));
        assertEquals(restricted, Files.getPosixFilePermissions(created));
    }
}
