package com.qlink.features.chat.repository;
import com.qlink.features.chat.entity.ChatRoom;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import java.util.*;
public interface ChatRoomRepository extends JpaRepository<ChatRoom,UUID> {
 @Query(value="select count(*) from chat_rooms where avatar_file_id=:id",nativeQuery=true)
 long avatarReferences(UUID id);
 @Query(value="select * from chat_rooms where avatar_file_id=:id",nativeQuery=true)
 Optional<ChatRoom> includingDeletedByAvatarFileId(UUID id);
 @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 @Query("select r from ChatRoom r where r.id=:id")
 Optional<ChatRoom> lockById(UUID id);
 Optional<ChatRoom> findByPersonalPairKey(String key);
 @Query(value="""
 select r.* from chat_rooms r
 join chat_room_members c on c.room_id=r.id and c.user_id=:viewer and c.deleted_at is null
 where r.deleted_at is null and (:archived is null or c.archived=:archived)
 order by c.pinned desc, (select max(m.created_at) from chat_messages m
   where m.room_id=r.id and m.deleted_at is null
   and (r.type='PERSONAL' or r.message_history_visibility='VIEW_ALL_MESSAGES' or m.created_at>=c.joined_at)
   and not exists(select 1 from message_hidden h where h.message_id=m.id and h.user_id=:viewer)) desc, r.id desc
 """, countQuery="""
 select count(*) from chat_rooms r
 join chat_room_members c on c.room_id=r.id and c.user_id=:viewer and c.deleted_at is null
 where r.deleted_at is null and (:archived is null or c.archived=:archived)
 """,nativeQuery=true)
 Page<ChatRoom> managedRooms(UUID viewer, Boolean archived, Pageable pageable);
}
