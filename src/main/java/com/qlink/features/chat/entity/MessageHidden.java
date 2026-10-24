package com.qlink.features.chat.entity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
@Entity @Table(name="message_hidden") @Getter @Setter @NoArgsConstructor
public class MessageHidden extends com.qlink.common.entity.BaseEntity {
 @Column(nullable=false,columnDefinition="binary(16)") private UUID messageId;
 @Column(nullable=false,columnDefinition="binary(16)") private UUID userId;
}
