package com.qlink.features.storage.repository;

import com.qlink.features.storage.entity.StoredFile;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {
    @Query(value="SELECT * FROM stored_files WHERE uploaded_by=:actor AND upload_key=:key",nativeQuery=true)
    Optional<StoredFile> byUploadKey(UUID actor, UUID key);
    @Query(value = "SELECT BIN_TO_UUID(f.id) FROM stored_files f WHERE f.voice_recording=TRUE AND ((f.deleted_at IS NOT NULL AND f.status='DELETED') OR (f.deleted_at IS NULL AND (f.created_at < :before OR EXISTS (SELECT 1 FROM chat_messages a WHERE a.file_id=f.id)) AND NOT EXISTS (SELECT 1 FROM chat_messages m WHERE m.file_id=f.id AND m.deleted_at IS NULL AND m.removed_at IS NULL))) LIMIT 100", nativeQuery = true)
    List<String> voiceCleanupCandidates(@Param("before") java.time.Instant before);

    // Bypass the soft-delete restriction ONLY for idempotent deletion/cleanup.
    @Query(value = "SELECT * FROM stored_files WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<StoredFile> lockIncludingDeleted(@Param("id") UUID id);

    @Query(value = "SELECT * FROM stored_files WHERE id = :id AND deleted_at IS NOT NULL AND status = 'DELETED'", nativeQuery = true)
    Optional<StoredFile> findTombstone(@Param("id") UUID id);
}
