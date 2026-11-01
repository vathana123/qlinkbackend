package com.qlink.features.chat.realtime;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/** Single-server leases. All access is serialized by ActivityService. */
@Component
public class PresenceRegistry {
    public record Session(UUID user, Jwt jwt, Instant touched) {}
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<UUID, Instant> latest = new HashMap<>();
    public synchronized boolean connect(String id, Jwt jwt, Instant now) {
        UUID user = UUID.fromString(jwt.getSubject());
        boolean wasOnline = online(user);
        sessions.putIfAbsent(id, new Session(user, jwt, now));
        latest.merge(user, now, (a,b) -> a.isAfter(b) ? a : b);
        return !wasOnline && online(user);
    }
    public synchronized void touch(String id, Instant now) {
        sessions.computeIfPresent(id, (key, old) -> {
            latest.merge(old.user(), now, (a,b) -> a.isAfter(b) ? a : b);
            return new Session(old.user(), old.jwt(), now);
        });
    }
    public synchronized Session remove(String id) {
        var removed=sessions.remove(id);
        if(removed!=null && !online(removed.user())) {
            var seen=latest.remove(removed.user());
            return new Session(removed.user(),removed.jwt(),seen==null?removed.touched():seen);
        }
        return removed;
    }
    public synchronized boolean online(UUID user) { return sessions.values().stream().anyMatch(s -> s.user().equals(user)); }
    public synchronized Map<String, Session> snapshot() { return Map.copyOf(sessions); }
}
