package com.qlink.features.storage.service;

import com.qlink.features.storage.entity.StoredFile;
import com.qlink.features.auth.service.SessionAuthorization;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class StorageAccessPolicy {
    private final SessionAuthorization sessions;
    public StorageAccessPolicy(SessionAuthorization sessions) { this.sessions=sessions; }
    public UUID actor(Jwt jwt) {
        return sessions.actor(jwt);
    }
    public UUID recheck(Jwt jwt) { return sessions.recheck(jwt); }
    public void requireOwner(UUID actor, StoredFile file) {
        // Return the same response for nonexistent and other users' files.
        if (!actor.equals(file.getUploadedBy())) throw StorageErrors.missing();
    }
}
