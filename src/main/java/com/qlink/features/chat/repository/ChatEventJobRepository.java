package com.qlink.features.chat.repository;
import com.qlink.features.chat.entity.ChatEventJob;
import java.util.*;
import java.time.Instant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
public interface ChatEventJobRepository extends JpaRepository<ChatEventJob,UUID> {
 @Query("select j.id from ChatEventJob j where j.finishedAt is null and j.nextAttemptAt<=:now order by j.nextAttemptAt,j.id")
 List<UUID> due(Instant now, Pageable page);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select j from ChatEventJob j where j.id=:id")
 Optional<ChatEventJob> lock(UUID id);
 @Modifying @Query("delete from ChatEventJob j where j.finishedAt<:before") int purge(Instant before);
}
