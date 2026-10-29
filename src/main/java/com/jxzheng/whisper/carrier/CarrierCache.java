package com.jxzheng.whisper.carrier;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;

import com.jxzheng.whisper.ops.StegoOperations;

/**
 * One decoded cover, shared by the embed preview and the embed itself.
 *
 * <p>The entry is keyed by path, size, and last-modified time and held through
 * a {@link SoftReference}. A miss reads the file once.
 */
public final class CarrierCache {

    private final Object lock = new Object();
    private Path path;
    private long size = -1;
    private long modified = -1;
    private SoftReference<BufferedImage> image = new SoftReference<>(null);

    public BufferedImage load(Path path) throws IOException {
        if (path == null) {
            throw new IOException("Could not read image: null");
        }
        long fileSize = Files.size(path);
        long fileModified = Files.getLastModifiedTime(path).toMillis();
        synchronized (lock) {
            BufferedImage cached = image.get();
            if (cached != null && path.equals(this.path) && fileSize == size && fileModified == modified) {
                return cached;
            }
        }
        BufferedImage loaded = StegoOperations.readImage(path);
        synchronized (lock) {
            this.path = path;
            this.size = fileSize;
            this.modified = fileModified;
            this.image = new SoftReference<>(loaded);
        }
        return loaded;
    }

    public void clear() {
        synchronized (lock) {
            path = null;
            size = -1;
            modified = -1;
            image = new SoftReference<>(null);
        }
    }
}
