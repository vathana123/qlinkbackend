package com.qlink.features.user.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="user_blocks") @Getter @Setter @NoArgsConstructor
public class UserBlock {
    @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(columnDefinition="binary(16)") private UUID id;
    @Column(nullable=false,columnDefinition="binary(16)") private UUID blockerId;
    @Column(nullable=false,columnDefinition="binary(16)") private UUID blockedId;
    @Column(nullable=false) private Instant createdAt=Instant.now();
}
