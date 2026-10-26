package com.qlink.features.chat.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="chat_event_jobs") @Getter @Setter @NoArgsConstructor
public class ChatEventJob extends com.qlink.common.entity.BaseEntity {
 @Column(nullable=false,columnDefinition="binary(16)") private UUID messageId;
 @Column(nullable=false,length=30) private String eventType;
 @Column(columnDefinition="binary(16)") private UUID onlyUser;
 private int attempts;
 @Column(nullable=false) private Instant nextAttemptAt;
 private Instant finishedAt;
}
