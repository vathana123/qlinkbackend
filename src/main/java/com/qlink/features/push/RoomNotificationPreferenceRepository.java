package com.qlink.features.push;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RoomNotificationPreferenceRepository extends JpaRepository<RoomNotificationPreference,UUID> {
 Optional<RoomNotificationPreference> findByRoomIdAndUserId(UUID room,UUID user);
}
