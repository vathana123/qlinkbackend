package com.qlink.features.chat.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="message_receipts") @Getter @Setter @NoArgsConstructor
public class MessageReceipt extends com.qlink.common.entity.BaseEntity {
 @Column(nullable=false,columnDefinition="binary(16)") private UUID messageId;
 @Column(nullable=false,columnDefinition="binary(16)") private UUID userId;
 @Column(nullable=false,columnDefinition="binary(16)") private UUID membershipId;
 private Instant deliveredAt;
 private Instant seenAt;
}
