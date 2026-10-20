package com.qlink.features.auth.entity;
import com.qlink.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "refresh_tokens") @SQLRestriction("deleted_at IS NULL")
@Getter @Setter @NoArgsConstructor
public class RefreshToken extends BaseEntity {
    @Column(nullable = false, columnDefinition = "binary(16)") private UUID sessionId;
    @Column(nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(nullable = false) private Instant expiresAt;
    private Instant revokedAt;
    @Column(columnDefinition = "binary(16)") private UUID replacedBy;
}
