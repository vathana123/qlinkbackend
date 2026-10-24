package com.qlink.features.auth.repository;
import com.qlink.features.auth.entity.AuthSession;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface AuthSessionRepository extends Repository<AuthSession, UUID> {
    @Query("""
        select count(s) from AuthSession s, com.qlink.features.user.entity.User u
        where s.id = :sessionId and s.userId = :userId and u.id = s.userId
        and s.revokedAt is null and s.expiresAt > :now
        and u.status = com.qlink.features.user.enums.UserStatus.ACTIVE
        """)
    long countActive(UUID sessionId, UUID userId, java.time.Instant now);
    Optional<AuthSession> findById(UUID id);
    AuthSession save(AuthSession session);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.id = :id")
    Optional<AuthSession> findLockedById(UUID id);
}
