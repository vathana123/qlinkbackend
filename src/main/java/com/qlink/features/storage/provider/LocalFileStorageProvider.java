package com.qlink.features.storage.provider;
import com.qlink.features.storage.config.StorageProperties;
import com.qlink.features.storage.service.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

@Component
public class LocalFileStorageProvider implements FileStorageProvider {
    private final Path root;
    public LocalFileStorageProvider(StorageProperties properties) {
        root = Path.of(properties.rootPath()).toAbsolutePath().normalize();
        try {
            Path current = root.getRoot();
            for (Path part : root) {
                current = current.resolve(part);
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    try { Files.createDirectory(current, permissions("rwx------")); }
                    catch (FileAlreadyExistsException ignored) { /* recheck below */ }
                }
                requireDirectory(current);
            }
            Path probe = Files.createTempFile(root, ".write-probe-", ".tmp", permissions("rw-------"));
            Files.delete(probe);
        } catch (IOException | SecurityException ex) {
            throw new IllegalStateException("Storage root must be a writable directory without symbolic links; check app.storage.root-path and directory permissions");
        }
    }
    private FileAttribute<?>[] permissions(String mode) {
        return root.getFileSystem().supportedFileAttributeViews().contains("posix")
            ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode))}
            : new FileAttribute<?>[0];
    }
    private void requireDirectory(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || !path.toRealPath().equals(path.toAbsolutePath().normalize())) throw new IOException("Unsafe storage directory");
    }
    private void checkRoot() throws IOException {
        Path current = root.getRoot();
        for (Path part : root) { current = current.resolve(part); requireDirectory(current); }
    }
    private Path resolve(String key) throws IOException {
        if (key == null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.]blob"))
            throw new IOException("Invalid storage key");
        checkRoot();
        return root.resolve(key);
    }
    private InputStream open(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException("Stored file unavailable");
        return Channels.newInputStream(Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)));
    }
    @Override public synchronized StoredBytes store(InputStream input, String key, long maxBytes, Validator validator) throws IOException {
        if (maxBytes <= 0) throw new IllegalArgumentException("Positive size limit required");
        Path target = resolve(key);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException("Storage key already exists");
        Path temporary = Files.createTempFile(root, ".upload-", ".part", permissions("rw-------"));
        try {
            MessageDigest hash;
            try { hash = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
            long size = 0;
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var output = Channels.newOutputStream(channel)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > maxBytes - size) throw StorageErrors.tooLarge();
                    output.write(buffer, 0, count); hash.update(buffer, 0, count); size += count;
                }
                if (size == 0) throw StorageErrors.invalid("File must not be empty");
                channel.force(true);
            }
            String type;
            try (var stream = new BufferedInputStream(open(temporary))) { type = validator.validate(stream); }
            checkRoot();
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, target); }
            return new StoredBytes(size, HexFormat.of().formatHex(hash.digest()), type);
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override public InputStream load(String key) throws IOException { return open(resolve(key)); }
    @Override public synchronized void delete(String key) throws IOException {
        Path path = resolve(key);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe stored file");
        Files.deleteIfExists(path);
    }
    @Override public boolean exists(String key) throws IOException {
        return Files.isRegularFile(resolve(key), LinkOption.NOFOLLOW_LINKS);
    }
}
