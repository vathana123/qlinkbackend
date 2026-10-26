package com.qlink.features.push;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
@Entity @Table(name="push_devices") @Getter @Setter @NoArgsConstructor
public class PushDevice extends com.qlink.common.entity.BaseEntity {
 @Column(nullable=false,columnDefinition="binary(16)") private UUID installationId;
 @Column(nullable=false,columnDefinition="binary(16)") private UUID userId;
 @Column(nullable=false,columnDefinition="binary(16)") private UUID sessionId;
 @Column(nullable=false,columnDefinition="text") private String token;
 @Column(nullable=false,length=64) private String tokenHash;
 @Column(nullable=false,length=10) private String platform;
}
