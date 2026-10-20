package com.qlink.features.user.repository;
import com.qlink.features.user.entity.User;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface UserRepository extends JpaRepository<User, UUID> {
    @Query("""
 select u from User u where u.id <> :viewer and u.status = com.qlink.features.user.enums.UserStatus.ACTIVE
 and (:target is null or u.id = :target) and exists (
 select m.id from ChatRoomMember m, ChatRoomMember mine, ChatRoom r
 where m.userId=u.id and mine.userId=:viewer and m.roomId=mine.roomId and r.id=m.roomId
 and r.type=com.qlink.features.chat.enums.ChatRoomType.PERSONAL)
 order by lower(u.displayName), u.id
 """)
 Page<User> contacts(UUID viewer, UUID target, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockById(UUID id);
    @Query("""
        select u from User u
        where u.id <> :viewer and u.status = com.qlink.features.user.enums.UserStatus.ACTIVE
        and ((:pattern is not null and (lower(u.displayName) like :pattern escape '!' or lower(u.username) like :pattern escape '!'))
          or (:email is not null and lower(trim(u.email)) = :email)
          or (:phone is not null and u.phoneNumber = :phone))
        order by lower(u.displayName), u.id
        """)
    Page<User> search(UUID viewer, String pattern, String email, String phone, Pageable pageable);
}
