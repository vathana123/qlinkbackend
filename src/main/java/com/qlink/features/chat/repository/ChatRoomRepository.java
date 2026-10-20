package com.qlink.features.chat.repository;
import com.qlink.features.chat.entity.ChatRoom;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import java.util.*;
public interface ChatRoomRepository extends JpaRepository<ChatRoom,UUID> {
 @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 @Query("select r from ChatRoom r where r.id=:id")
 Optional<ChatRoom> lockById(UUID id);
 Optional<ChatRoom> findByPersonalPairKey(String key);
 @Query("select r from ChatRoom r where r.id in (select m.roomId from ChatRoomMember m where m.userId = :viewer) order by r.lastMessageAt desc, r.id desc")
 Page<ChatRoom> rooms(UUID viewer, Pageable pageable);
}
