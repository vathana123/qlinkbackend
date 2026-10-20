package com.qlink.features.storage.service;

import com.qlink.features.storage.entity.StoredFile;
import com.qlink.features.auth.repository.AuthSessionRepository;
import com.qlink.features.user.service.UserService;
import com.qlink.common.exception.ApiException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

@Component
public class StorageAccessPolicy {
    private final UserService users;
    private final AuthSessionRepository sessions;
    public StorageAccessPolicy(UserService users, AuthSessionRepository sessions) { this.users=users; this.sessions=sessions; }
    public UUID actor(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        users.requireActive(id);
        if (jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(Instant.now()) ||
            sessions.findById(UUID.fromString(jwt.getClaimAsString("sid")))
                .filter(s -> s.getUserId().equals(id) && s.getRevokedAt()==null && s.getExpiresAt().isAfter(Instant.now())).isEmpty())
            throw ApiException.unauthorized("SESSION_UNAVAILABLE","Authentication session is unavailable");
        return id;
    }
    public void requireOwner(UUID actor, StoredFile file) {
        // Return the same response for nonexistent and other users' files.
        if (!actor.equals(file.getUploadedBy())) throw StorageErrors.missing();
    }
}
