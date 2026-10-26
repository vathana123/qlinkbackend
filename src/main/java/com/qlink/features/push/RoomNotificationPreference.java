package com.qlink.features.push;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
@Entity @Table(name="room_notification_preferences") @Getter @Setter @NoArgsConstructor
public class RoomNotificationPreference extends com.qlink.common.entity.BaseEntity {
 @Column(nullable=false,columnDefinition="binary(16)") private UUID roomId;
 @Column(nullable=false,columnDefinition="binary(16)") private UUID userId;
 private boolean muted;
}
