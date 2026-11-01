package com.qlink.features.user.repository;
import com.qlink.features.user.entity.UserBlock;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import java.util.*;
public interface UserBlockRepository extends JpaRepository<UserBlock,UUID> {
    Optional<UserBlock> findByBlockerIdAndBlockedId(UUID blockerId,UUID blockedId);
    @Query("select count(b)>0 from UserBlock b where (b.blockerId=:a and b.blockedId=:b) or (b.blockerId=:b and b.blockedId=:a)")
    boolean between(UUID a,UUID b);
    @Query("select b from UserBlock b where b.blockerId=:viewer order by b.createdAt desc,b.id desc")
    Page<UserBlock> blocked(UUID viewer,Pageable page);
}
