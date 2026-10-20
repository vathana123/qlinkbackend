package com.qlink.features.storage.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
@ConfigurationProperties("app.storage")
public record StorageProperties(String rootPath, DataSize maxFileSize) {
    public StorageProperties {
        if (rootPath == null || rootPath.isBlank() || rootPath.contains("${")) throw new IllegalArgumentException("app.storage.root-path must be configured");
        if (maxFileSize == null || maxFileSize.toBytes() <= 0) throw new IllegalArgumentException("app.storage.max-file-size must be positive");
    }
}
