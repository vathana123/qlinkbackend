package com.qlink.features.chat.repository;
import com.qlink.features.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.*;
public interface ChatMessageRepository extends JpaRepository<ChatMessage,UUID> {
 @Query(value="select * from chat_messages where file_id=:fileId limit 1",nativeQuery=true)
 Optional<ChatMessage> includingDeletedByFileId(UUID fileId);
 @Query(value="select count(*) from chat_messages where file_id=:fileId and deleted_at is null and removed_at is null",nativeQuery=true)
 long liveFileReferences(UUID fileId);
 @Query(value="""
 select count(*) from chat_messages m
 join chat_rooms r on r.id=m.room_id and r.deleted_at is null
 join chat_room_members c on c.room_id=r.id and c.user_id=:viewer and c.deleted_at is null
 where m.file_id=:fileId and m.deleted_at is null and m.removed_at is null
 and (r.type='PERSONAL' or r.message_history_visibility='VIEW_ALL_MESSAGES' or m.created_at>=c.joined_at)
 and not exists(select 1 from message_hidden h where h.message_id=m.id and h.user_id=:viewer)
 """,nativeQuery=true)
 long readableFileReferences(UUID fileId, UUID viewer);
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
