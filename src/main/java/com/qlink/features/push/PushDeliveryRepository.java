package com.qlink.features.push;
import java.util.*;
import java.time.Instant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
public interface PushDeliveryRepository extends JpaRepository<PushDelivery,UUID> {
 @Query("select p.id from PushDelivery p where p.finishedAt is null and p.nextAttemptAt<=:now order by p.nextAttemptAt,p.id")
 List<UUID> due(Instant now,Pageable page);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from PushDelivery p where p.id=:id") Optional<PushDelivery> lock(UUID id);
 @Modifying @Query("delete from PushDelivery p where p.finishedAt<:before") int purge(Instant before);
}
