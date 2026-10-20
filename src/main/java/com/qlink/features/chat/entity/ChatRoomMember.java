package com.qlink.features.chat.entity;
import com.qlink.common.entity.BaseEntity;
import com.qlink.features.chat.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="chat_room_members") @SQLRestriction("deleted_at IS NULL")
@Getter @Setter @NoArgsConstructor
public class ChatRoomMember extends BaseEntity { @Column(nullable=false,columnDefinition="binary(16)") private UUID roomId;
 @Column(nullable=false,columnDefinition="binary(16)") private UUID userId;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20)
 private ChatRoomMemberRole role = ChatRoomMemberRole.MEMBER;
 @Column(nullable=false) private Instant joinedAt; }
