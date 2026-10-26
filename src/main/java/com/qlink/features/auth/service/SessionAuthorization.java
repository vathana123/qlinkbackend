package com.qlink.features.auth.service;
import lombok.RequiredArgsConstructor;

import com.qlink.common.exception.ApiException;
import com.qlink.features.auth.repository.AuthSessionRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.time.Instant;
import java.util.UUID;

/** Shared authorization after cryptographic JWT validation. Never caches across requests. */
@Service
@RequiredArgsConstructor
public class SessionAuthorization {
    private static final String CHECKED = SessionAuthorization.class.getName() + ".checked";
    private final AuthSessionRepository sessions;



    public UUID actor(Jwt jwt) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            var request = servlet.getRequest();
            if (jwt.equals(request.getAttribute(CHECKED)) && unexpired(jwt))
                return UUID.fromString(jwt.getSubject());
            UUID actor = recheck(jwt);
            request.setAttribute(CHECKED, jwt);
            return actor;
        }
        // Socket frames have no HTTP request cache: recheck each authorized operation.
        return recheck(jwt);
    }

    /** Use after slow uploads and for socket delivery, even within an HTTP request. */
    public UUID recheck(Jwt jwt) {
        UUID user, session;
        try {
            user = UUID.fromString(jwt.getSubject());
            session = UUID.fromString(jwt.getClaimAsString("sid"));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw unavailable();
        }
        if (!unexpired(jwt) || sessions.countActive(session, user, Instant.now()) != 1)
            throw unavailable();
        return user;
    }

    private boolean unexpired(Jwt jwt) {
        return jwt.getExpiresAt() != null && jwt.getExpiresAt().isAfter(Instant.now());
    }

    private ApiException unavailable() {
        return ApiException.unauthorized("SESSION_UNAVAILABLE", "Authentication session is unavailable");
    }
}
