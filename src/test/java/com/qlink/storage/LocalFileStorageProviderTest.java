package com.qlink.storage;

import com.qlink.features.storage.config.StorageProperties;
import com.qlink.features.storage.provider.LocalFileStorageProvider;
import com.qlink.features.storage.service.StorageErrors;
import com.qlink.common.exception.ApiException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class LocalFileStorageProviderTest {
    @TempDir Path directory;
    LocalFileStorageProvider provider;
    @BeforeEach void setup() { provider=new LocalFileStorageProvider(new StorageProperties(directory.toString(),DataSize.ofMegabytes(25))); }
    String key() { return UUID.randomUUID()+".blob"; }
    long count() throws IOException { try(var files=Files.list(directory)) { return files.count(); } }
    @Test void streamsHashesValidatesBeforePublishAndDoesNotCloseCallerInput() throws Exception {
        String key=key(); byte[] bytes="hello storage".getBytes();
        var input=new ByteArrayInputStream(bytes) { @Override public void close() { fail("Caller owns input"); } };
        var stored=provider.store(input,key,bytes.length,in -> {
            assertThat(provider.exists(key)).isFalse();
            assertThat(in.readAllBytes()).isEqualTo(bytes); return "text/plain";
        });
        assertThat(stored.size()).isEqualTo(bytes.length);
        assertThat(stored.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        try(var stream=provider.load(key)) { assertThat(stream.readAllBytes()).isEqualTo(bytes); }
        assertThat(count()).isEqualTo(1);
        provider.delete(key); provider.delete(key); assertThat(count()).isZero();
    }
    @Test void rejectsOversizeWhileStreamingAndCleansPartial() throws Exception {
        assertThatThrownBy(() -> provider.store(new ByteArrayInputStream(new byte[21]),key(),20,in -> "image/png"))
            .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FILE_TOO_LARGE");
        assertThat(count()).isZero();
    }
    @Test void emptyValidationAndReadFailuresLeaveNoFiles() throws Exception {
        assertThatThrownBy(() -> provider.store(new ByteArrayInputStream(new byte[0]),key(),20,in -> "text/plain")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> provider.store(new ByteArrayInputStream(new byte[]{1}),key(),20,in -> { throw StorageErrors.unsupported(); })).isInstanceOf(ApiException.class);
        var input=new InputStream() { @Override public int read() throws IOException { throw new IOException("simulated read failure"); } };
        assertThatThrownBy(() -> provider.store(input,key(),20,in -> "text/plain")).isInstanceOf(IOException.class);
        assertThat(count()).isZero();
    }
    @Test void rejectsTraversalAbsolutePathsAndNonServerKeys() {
        for(String key:List.of("../secret","..","C:/secret","/etc/passwd","a/b","a%2fb","file.txt",UUID.randomUUID()+".blob/other")) {
            assertThatThrownBy(() -> provider.load(key)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> provider.delete(key)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> provider.exists(key)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> provider.store(InputStream.nullInputStream(),key,20,in -> "text/plain")).isInstanceOf(IOException.class);
        }
    }
    @Test void existingKeysAreNeverOverwritten() throws Exception {
        String key=key(); Files.writeString(directory.resolve(key),"original");
        assertThatThrownBy(() -> provider.store(new ByteArrayInputStream(new byte[]{1}),key,20,in -> "text/plain")).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(directory.resolve(key))).isEqualTo("original");
    }
    @Test void rootMustBeWritableDirectoryAndStartupCreatesNestedDirectories() throws Exception {
        Path invalid=directory.resolve("regular-file"); Files.writeString(invalid,"x");
        assertThatThrownBy(() -> new LocalFileStorageProvider(new StorageProperties(invalid.toString(),DataSize.ofBytes(20))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("writable directory");
        var nested=new LocalFileStorageProvider(new StorageProperties(directory.resolve("nested/files").toString(),DataSize.ofBytes(20)));
        assertThat(nested.exists(key())).isFalse();
    }
    @Test void changedRootFailsSafely() throws Exception {
        Files.delete(directory); Files.writeString(directory,"not a directory");
        try { assertThatThrownBy(() -> provider.store(new ByteArrayInputStream(new byte[]{1}),key(),20,in -> "text/plain")).isInstanceOf(IOException.class); }
        finally { Files.delete(directory); Files.createDirectory(directory); }
    }
    @Test void symlinkFilesAndRootsAreRejected() throws Exception {
        Path outside=Files.createTempFile("qlink-outside-", ".txt");
        try {
            Files.writeString(outside,"untouched");
            Path link=directory.resolve(key());
            try { Files.createSymbolicLink(link,outside); }
            catch (FileSystemException | UnsupportedOperationException ex) { Assumptions.abort("Host does not permit symbolic link creation"); }
            assertThatThrownBy(() -> provider.load(link.getFileName().toString())).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> provider.delete(link.getFileName().toString())).isInstanceOf(IOException.class);
            assertThat(provider.exists(link.getFileName().toString())).isFalse();
            Path rootLink=directory.resolve("root-link"); Files.createSymbolicLink(rootLink,outside.getParent());
            assertThatThrownBy(() -> new LocalFileStorageProvider(new StorageProperties(rootLink.toString(),DataSize.ofBytes(20))))
                .isInstanceOf(IllegalStateException.class);
            assertThat(Files.readString(outside)).isEqualTo("untouched");
        } finally { Files.deleteIfExists(outside); }
    }
}
