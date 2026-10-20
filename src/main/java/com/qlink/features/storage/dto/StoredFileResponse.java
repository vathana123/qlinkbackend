package com.qlink.features.storage.dto;
import com.qlink.features.storage.enums.*;
import java.time.Instant;
import java.util.UUID;
public record StoredFileResponse(UUID id, String originalName, String contentType, long sizeBytes,
                                 FileCategory category, StoredFileStatus status, Instant createdAt) {}
