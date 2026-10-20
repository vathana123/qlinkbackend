package com.qlink.features.auth.repository;
import com.qlink.features.auth.entity.AuthSession;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface AuthSessionRepository extends Repository<AuthSession, UUID> {
    Optional<AuthSession> findById(UUID id);
    AuthSession save(AuthSession session);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.id = :id")
    Optional<AuthSession> findLockedById(UUID id);
}
