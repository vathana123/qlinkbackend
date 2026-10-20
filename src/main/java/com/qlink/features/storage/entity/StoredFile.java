package com.qlink.features.storage.entity;
import com.qlink.common.entity.BaseEntity;
import com.qlink.features.storage.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;
import java.util.UUID;
@Entity @Table(name = "stored_files") @SQLRestriction("deleted_at IS NULL")
@Getter @Setter @NoArgsConstructor
public class StoredFile extends BaseEntity {
    @Column(nullable=false, length=255) private String originalName;
    @Column(nullable=false, unique=true, length=500) private String storageKey;
    @Column(nullable=false, length=150) private String contentType;
    @Column(nullable=false) private long sizeBytes;
    @Column(nullable=false, length=64) private String checksumSha256;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=30) private FileCategory category;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=30) private StoredFileStatus status = StoredFileStatus.PENDING;
    @Column(nullable=false, columnDefinition="binary(16)") private UUID uploadedBy;
    @Column(nullable=false) private boolean voiceRecording;
    public void markDeleted(UUID actor) { status = StoredFileStatus.DELETED; softDelete(actor); }
}
