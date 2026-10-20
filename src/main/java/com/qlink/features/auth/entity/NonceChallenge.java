package com.qlink.features.auth.entity;
import com.qlink.common.entity.BaseEntity;
import com.qlink.features.auth.enums.AuthProvider;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "nonce_challenges")
@Getter @Setter @NoArgsConstructor
public class NonceChallenge extends BaseEntity {
    @Column(nullable = false, unique = true, length = 64) private String nonceHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private AuthProvider provider;
    @Column(nullable = false) private Instant expiresAt;
    private Instant consumedAt;
}
