package com.qlink.features.auth.repository;
import com.qlink.features.auth.entity.RefreshToken;
import org.springframework.data.repository.Repository;
import java.util.*;
public interface RefreshTokenRepository extends Repository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String hash);
    RefreshToken save(RefreshToken token);
}
