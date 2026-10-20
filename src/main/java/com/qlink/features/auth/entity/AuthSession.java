package com.qlink.features.auth.entity;
import com.qlink.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "auth_sessions") @SQLRestriction("deleted_at IS NULL")
@Getter @Setter @NoArgsConstructor
public class AuthSession extends BaseEntity {
    @Column(nullable = false, columnDefinition = "binary(16)") private UUID userId;
    @Column(nullable = false) private Instant expiresAt;
    private Instant revokedAt;
}
