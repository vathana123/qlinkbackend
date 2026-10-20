package com.qlink.features.storage.service;
import java.io.*;
/** Keys are server-generated identifiers, never user filenames or paths. */
public interface FileStorageProvider {
    record StoredBytes(long size, String sha256, String contentType) {}
    @FunctionalInterface interface Validator { String validate(InputStream input) throws IOException; }
    /**
     * Streams to private staging, enforces limit, hashes, validates, then publishes.
     * Does not close caller's input. Closes validation stream and removes partial files on failure.
     */
    StoredBytes store(InputStream input, String key, long maxBytes, Validator validator) throws IOException;
    /** Caller must close the returned stream. */
    InputStream load(String key) throws IOException;
    void delete(String key) throws IOException;
    boolean exists(String key) throws IOException;
}
