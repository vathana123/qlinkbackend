package com.qlink.features.chat.repository;
import com.qlink.features.chat.entity.ChatRoomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ChatMemberRepository extends JpaRepository<ChatRoomMember,UUID> {
 List<ChatRoomMember> findByRoomId(UUID roomId);
 long countByUserIdAndPinnedTrue(UUID userId);
 @org.springframework.data.jpa.repository.Query(value="select * from chat_room_members where room_id=:roomId and user_id=:userId order by created_at desc,id desc limit 1",nativeQuery=true)
 Optional<ChatRoomMember> latestMembership(UUID roomId, UUID userId);
}
