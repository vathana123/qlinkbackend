package com.qlink.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.Instant;
import java.util.UUID;

@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "binary(16)")
    private UUID id;
    @CreatedDate @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @LastModifiedDate @Column(nullable = false)
    private Instant updatedAt;
    private Instant deletedAt;
    @CreatedBy @Column(columnDefinition = "binary(16)", updatable = false)
    private UUID createdBy;
    @LastModifiedBy @Column(columnDefinition = "binary(16)")
    private UUID updatedBy;
    @Column(columnDefinition = "binary(16)")
    private UUID deletedBy;

    public void softDelete(UUID actor) {
        deletedAt = Instant.now();
        deletedBy = actor;
        updatedBy = actor;
    }

    public void recordActor(UUID actor) {
        updatedBy = actor;
    }
}
