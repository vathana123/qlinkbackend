package com.qlink.features.chat.repository;
import com.qlink.features.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.*;
public interface ChatMessageRepository extends JpaRepository<ChatMessage,UUID> {
 @Query(value="select * from chat_messages where file_id=:fileId",nativeQuery=true)
 Optional<ChatMessage> includingDeletedByFileId(UUID fileId);
 @Query(value="select count(*) from message_hidden where message_id=:message and user_id=:viewer",nativeQuery=true)
 long hiddenCount(UUID message,UUID viewer);
 // Include tombstones: an idempotency key cannot be reused after soft deletion.
 @Query(value="select * from chat_messages where sender_id=:sender and client_message_id=:key",nativeQuery=true)
 Optional<ChatMessage> previous(UUID sender, UUID key);
 @Query("select m from ChatMessage m where m.roomId=:room and (:since is null or m.createdAt >= :since) and not exists (select h.id from MessageHidden h where h.messageId=m.id and h.userId=:viewer) order by m.createdAt desc,m.id desc")
 List<ChatMessage> visibleLatest(UUID room, UUID viewer, Instant since, Pageable pageable);
 Optional<ChatMessage> findFirstByRoomIdOrderByCreatedAtDescIdDesc(UUID roomId);
 @Query("select m from ChatMessage m where m.roomId=:room and (:since is null or m.createdAt >= :since) and (:before is null or m.createdAt < :before or (m.createdAt=:before and m.id < :id)) order by m.createdAt desc,m.id desc")
 List<ChatMessage> history(UUID room, Instant since, Instant before, UUID id, Pageable pageable);
}
