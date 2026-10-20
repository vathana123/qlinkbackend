package com.qlink.features.auth.repository;
import com.qlink.features.auth.entity.NonceChallenge;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface NonceChallengeRepository extends Repository<NonceChallenge, UUID> {
    NonceChallenge save(NonceChallenge nonce);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<NonceChallenge> findByNonceHashAndDeletedAtIsNull(String hash);
}
