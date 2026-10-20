package com.qlink.features.chat.repository;
import com.qlink.features.chat.entity.ChatRoomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ChatMemberRepository extends JpaRepository<ChatRoomMember,UUID> {
 List<ChatRoomMember> findByRoomId(UUID roomId);
}
