package com.qlink.features.chat.entity;
import com.qlink.common.entity.BaseEntity;
import com.qlink.features.chat.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="chat_rooms") @SQLRestriction("deleted_at IS NULL")
@Getter @Setter @NoArgsConstructor
public class ChatRoom extends BaseEntity { @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private ChatRoomType type = ChatRoomType.PERSONAL;
 @Column(nullable=false,columnDefinition="binary(16)") private UUID creatorId;
 @Column(length=73) private String personalPairKey;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=30)
 private MessageHistoryVisibility messageHistoryVisibility = MessageHistoryVisibility.VIEW_ALL_MESSAGES;
 @Column(length=255) private String groupName;
 private Instant lastMessageAt; }
