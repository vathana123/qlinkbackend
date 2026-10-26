package com.qlink.features.push;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
@Entity @Table(name="push_deliveries") @Getter @Setter @NoArgsConstructor
public class PushDelivery extends com.qlink.common.entity.BaseEntity {
 @Column(nullable=false,columnDefinition="binary(16)") private UUID messageId;
 @Column(nullable=false,columnDefinition="binary(16)") private UUID deviceId;
 @Column(nullable=false,columnDefinition="binary(16)") private UUID recipientId;
 private int attempts;
 @Column(nullable=false) private java.time.Instant nextAttemptAt;
 private java.time.Instant finishedAt;
}
