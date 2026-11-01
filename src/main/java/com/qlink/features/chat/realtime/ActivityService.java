package com.qlink.features.chat.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.*;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class ActivityService {
    public record Presence(String type, UUID userId, Boolean online, Instant lastSeenAt) {}
    public record Typing(String type, UUID roomId, UUID userId, boolean typing, Instant expiresAt) {}
    public record Permissions(String type, UUID userId, boolean canMessage) {}
    private record Typist(String session, UUID room, UUID user) {}
    private final PresenceRegistry registry;
    private final ActivityAccess access;
    private final SocketAccess sockets;
    private final SimpMessagingTemplate broker;
    private final Map<String, Set<UUID>> watches = new HashMap<>();
    private final Map<Typist, Instant> typing = new HashMap<>();
    private final Map<UUID, Instant> checkpoints = new HashMap<>();

    @EventListener
    public synchronized void connected(SessionConnectedEvent event) {
        if (!(event.getUser() instanceof JwtAuthenticationToken auth) || !sockets.active(auth.getToken())) return;
        String id = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        if (id == null) return;
        var now = Instant.now();
        if (registry.connect(id, auth.getToken(), now)) {
            UUID user = UUID.fromString(auth.getName());
            checkpoint(user, now);
            publishPresence(user);
        }
    }
    @EventListener
    public synchronized void disconnected(SessionDisconnectEvent event) { disconnect(event.getSessionId()); }
    private void disconnect(String id) {
        watches.remove(id);
        var session = registry.remove(id);
        var removed = typing.keySet().stream().filter(t -> t.session().equals(id)).toList();
        removed.forEach(t -> { typing.remove(t); publishTyping(t.room(), t.user()); });
        if (session != null && !registry.online(session.user())) {
            // The last observed heartbeat is the last confirmed activity, not delayed timeout detection.
            checkpoint(session.user(), session.touched());
            checkpoints.remove(session.user());
            publishPresence(session.user());
        }
    }
    private void checkpoint(UUID user, Instant now) { access.persist(user, now); checkpoints.put(user, now); }
    public synchronized Presence presence(UUID viewer, UUID target) {
        var visibility = access.visibility(viewer,target);
        if (!visibility.online() && !visibility.lastSeen()) return new Presence("PRESENCE", target, null, null);
        boolean online = registry.online(target);
        return new Presence("PRESENCE", target, visibility.online() ? online : null, online || !visibility.lastSeen() ? null : access.lastSeen(target));
    }
    public synchronized void watch(String session, UUID viewer, List<UUID> targets) {
        if (targets == null || targets.size() > 100 || targets.stream().anyMatch(Objects::isNull)) return;
        if (!registry.snapshot().containsKey(session)) return;
        watches.put(session, new HashSet<>(targets));
        for (UUID target : new HashSet<>(targets)) { send(viewer, presence(viewer, target)); send(viewer,new Permissions("PERMISSIONS",target,access.canMessage(viewer,target))); }
    }
    public synchronized void typing(String session, UUID viewer, UUID room, boolean active) {
        if (room == null || !registry.snapshot().containsKey(session)) return;
        var key = new Typist(session, room, viewer);
        var now = Instant.now();
        var previous = typing.get(key);
        if (active && previous != null && previous.minusSeconds(4).isAfter(now)) return; // <= one refresh / 2s
        if (access.typingRecipients(viewer, room).isEmpty()) return;
        if (active) {
            // One composer per socket; bounds memory even for malicious clients switching rooms.
            var old = typing.keySet().stream().filter(t -> t.session().equals(session) && !t.room().equals(room)).toList();
            old.forEach(t -> { typing.remove(t); publishTyping(t.room(), t.user()); });
            typing.put(key, now.plusSeconds(6));
        } else if (typing.remove(key) == null) return;
        publishTyping(room, viewer);
    }
    private void publishTyping(UUID room, UUID actor) {
        var expiry = typing.entrySet().stream().filter(e -> e.getKey().room().equals(room) && e.getKey().user().equals(actor))
            .map(Map.Entry::getValue).max(Comparator.naturalOrder()).orElse(Instant.now());
        var event = new Typing("TYPING", room, actor, expiry.isAfter(Instant.now()), expiry);
        for (UUID viewer : access.typingRecipients(actor, room)) send(viewer, event);
    }
    public synchronized void publishPresence(UUID target) {
        var sessions = registry.snapshot();
        var viewers = new HashSet<UUID>();
        watches.forEach((id, targets) -> { if (targets.contains(target) && sessions.containsKey(id)) viewers.add(sessions.get(id).user()); });
        for (UUID viewer : viewers) send(viewer, presence(viewer, target));
    }
    @org.springframework.transaction.event.TransactionalEventListener(phase=org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public synchronized void privacyChanged(com.qlink.features.user.service.PrivacyChanged change) {
        publishPresence(change.user());
        if(change.other()!=null) {
            publishPresence(change.other());
            send(change.user(),new Permissions("PERMISSIONS",change.other(),access.canMessage(change.user(),change.other())));
            send(change.other(),new Permissions("PERMISSIONS",change.user(),access.canMessage(change.other(),change.user())));
        }
    }
    private void send(UUID user, Object event) { broker.convertAndSendToUser(user.toString(), "/queue/activity", event); }
    @Scheduled(fixedDelay = 5000)
    public synchronized void sweep() {
        var now = Instant.now();
        registry.snapshot().forEach((id, session) -> {
            if (session.touched().plusSeconds(45).isBefore(now) || !sockets.active(session.jwt())) disconnect(id);
            else if (checkpoints.getOrDefault(session.user(), Instant.EPOCH).plusSeconds(60).isBefore(now)) checkpoint(session.user(), session.touched());
        });
        var expired = typing.entrySet().stream().filter(e -> !e.getValue().isAfter(now)).map(Map.Entry::getKey).toList();
        expired.forEach(key -> { typing.remove(key); publishTyping(key.room(), key.user()); });
    }
}
