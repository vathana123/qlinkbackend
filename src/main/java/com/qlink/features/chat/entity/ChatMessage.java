package com.qlink.features.chat.entity;
import com.qlink.common.entity.BaseEntity;
import com.qlink.features.chat.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="chat_messages") @SQLRestriction("deleted_at IS NULL")
@Getter @Setter @NoArgsConstructor
public class ChatMessage extends BaseEntity { @Column(nullable=false,columnDefinition="binary(16)") private UUID roomId;
 @Column(nullable=false,columnDefinition="binary(16)") private UUID senderId;
 @Column(nullable=false,columnDefinition="binary(16)") private UUID clientMessageId;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private MessageType type = MessageType.TEXT;
 @Column(nullable=false,columnDefinition="text") private String content;
 @Column(columnDefinition="binary(16)") private UUID fileId;
 private UUID replyToId;
 private Instant editedAt;
 private Instant removedAt;
 private UUID removedBy;
 private long revision;
 private int recipientCount;
 private int deliveredCount;
 private int seenCount;
 private Long durationMs;
 private String audioContentType;
 private Long audioSizeBytes;
}
