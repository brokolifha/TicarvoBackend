package com.ticarvo.shared.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;

/** Stores images under ticarvo.storage.image-dir; on the VPS this must be a persistent, backed-up volume (REL-M4-IMAGES). */
@Component
public class LocalImageStore implements ImageStore {
    private static final String KEY = "[0-9a-f-]{36}/[0-9a-f-]{36}/[0-9a-f-]{36}(_(320|1024))?\\.(jpg|png)";
    private final Path root;
    public LocalImageStore(@Value("${ticarvo.storage.image-dir:}") String directory) {
        if (directory.isBlank()) throw new IllegalStateException("ticarvo.storage.image-dir is required.");
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }
    public void put(String key, byte[] content) {
        try {
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE_NEW);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
    public boolean exists(String key) { return Files.isRegularFile(resolve(key)); }
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        // Defence in depth: keys are generated from UUIDs, but never let one point outside the root.
        if (!key.matches(KEY) || !target.startsWith(root)) throw new IllegalArgumentException("Invalid image key.");
        return target;
    }
}
