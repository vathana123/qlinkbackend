package com.qlink.features.chat.service;
import lombok.RequiredArgsConstructor;

import com.qlink.common.exception.ApiException;
import com.qlink.features.chat.dto.ChatDtos.*;
import com.qlink.features.chat.entity.*;
import com.qlink.features.user.service.UserService;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Literal substring search, bounded in SQL and scoped to active memberships. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ChatSearchService {
    private final EntityManager em;
    private final ChatService chat;
    private final UserService users;

    public record Results<T>(List<T> items, String nextCursor) {}
    public record Match(Room room, Message message) {}
    public record Context(List<Message> items, String nextCursor, String newerCursor) {}

    static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH", message);
    }
    public static String pattern(String raw) {
        String term = raw == null ? "" : raw.strip();
        if (term.length() < 2 || term.length() > 100 || term.chars().anyMatch(Character::isISOControl))
            throw invalid("Search using 2–100 characters");
        return "%" + term.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
    private static void size(int size) {
        if (size < 1 || size > 50) throw invalid("Page size must be 1–50");
    }
    public record Cursor(Instant time, UUID id) {
        public static Cursor read(String value, String scope) {
            if (value == null) return null;
            try {
                if (value.length() > 256) throw new IllegalArgumentException();
                var parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 3 || !parts[0].equals(scope)) throw new IllegalArgumentException();
                return new Cursor(Instant.parse(parts[1]), UUID.fromString(parts[2]));
            } catch (RuntimeException e) { throw invalid("Invalid search cursor"); }
        }
        public String encode(String scope) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString((scope + "|" + time + "|" + id).getBytes(StandardCharsets.UTF_8));
        }
    }
    private static String cursor(String scope, com.qlink.common.entity.BaseEntity entity) {
        return new Cursor(entity.getCreatedAt(), entity.getId()).encode(scope);
    }
    // Hibernate applies each entity's deleted_at restriction, including subqueries.
    private static final String ACCESS = """
        exists (select member.id from ChatRoomMember member, ChatRoom r
          where member.roomId=m.roomId and r.id=m.roomId and member.userId=:viewer
          and (r.type=com.qlink.features.chat.enums.ChatRoomType.PERSONAL
            or r.messageHistoryVisibility=com.qlink.features.chat.enums.MessageHistoryVisibility.VIEW_ALL_MESSAGES
            or m.createdAt >= member.joinedAt))
        and not exists (select h.id from MessageHidden h where h.messageId=m.id and h.userId=:viewer)
        """;

    public Results<Room> rooms(UUID viewer, String term, String cursor, int size) {
        users.requireActive(viewer); size(size);
        String pattern = pattern(term);
        var boundary = Cursor.read(cursor, "rooms");
        String sql = """
            select r from ChatRoom r where exists
              (select member.id from ChatRoomMember member where member.roomId=r.id and member.userId=:viewer)
            and (lower(r.groupName) like :pattern escape '!'
              or (r.type=com.qlink.features.chat.enums.ChatRoomType.PERSONAL and exists
                (select peer.id from ChatRoomMember peer, User u where peer.roomId=r.id
                  and peer.userId<>:viewer and u.id=peer.userId
                  and (lower(u.displayName) like :pattern escape '!' or lower(u.username) like :pattern escape '!'))))
            """;
        if (boundary != null) sql += " and (r.createdAt<:time or (r.createdAt=:time and r.id<:id))";
        var query = em.createQuery(sql + " order by r.createdAt desc,r.id desc", ChatRoom.class)
            .setParameter("viewer", viewer).setParameter("pattern", pattern).setMaxResults(size + 1);
        if (boundary != null) query.setParameter("time", boundary.time()).setParameter("id", boundary.id());
        var found = query.getResultList();
        var page = found.subList(0, Math.min(size, found.size()));
        return new Results<>(page.stream().map(r -> chat.roomDto(viewer, chat.requireRoom(viewer, r.getId()))).toList(),
            found.size() > size ? cursor("rooms", page.get(page.size()-1)) : null);
    }

    public Results<Match> messages(UUID viewer, UUID roomId, String term, UUID sender, Instant from, Instant to, String cursor, int size) {
        users.requireActive(viewer); size(size);
        String pattern = pattern(term);
        if (roomId != null) chat.requireRoom(viewer, roomId);
        if (from != null && to != null && !from.isBefore(to)) throw invalid("From must precede to");
        String scope = roomId == null ? "messages" : "search:" + roomId;
        var boundary = Cursor.read(cursor, scope);
        String sql = "select m from ChatMessage m where " + ACCESS +
            " and m.type=com.qlink.features.chat.enums.MessageType.TEXT and m.removedAt is null and lower(m.content) like :pattern escape '!'";
        if (roomId != null) sql += " and m.roomId=:room";
        if (sender != null) sql += " and m.senderId=:sender";
        if (from != null) sql += " and m.createdAt>=:from";
        if (to != null) sql += " and m.createdAt<:to";
        if (boundary != null) sql += " and (m.createdAt<:time or (m.createdAt=:time and m.id<:id))";
        var query = em.createQuery(sql + " order by m.createdAt desc,m.id desc", ChatMessage.class)
            .setParameter("viewer", viewer).setParameter("pattern", pattern).setMaxResults(size + 1);
        if (roomId != null) query.setParameter("room", roomId);
        if (sender != null) query.setParameter("sender", sender);
        if (from != null) query.setParameter("from", from);
        if (to != null) query.setParameter("to", to);
        if (boundary != null) query.setParameter("time", boundary.time()).setParameter("id", boundary.id());
        var found = query.getResultList();
        var page = found.subList(0, Math.min(size, found.size()));
        var roomCache = new HashMap<UUID, Room>();
        return new Results<>(page.stream().map(m -> new Match(roomCache.computeIfAbsent(m.getRoomId(),
            id -> chat.room(viewer, id)), chat.messageDto(viewer, m))).toList(),
            found.size() > size ? cursor(scope, page.get(page.size()-1)) : null);
    }

    public Context context(UUID viewer, UUID roomId, UUID messageId, int size) {
        size(size);
        var room = chat.requireRoom(viewer, roomId);
        var target = chat.visibleMessage(viewer, room, messageId);
        if (target.getRemovedAt() != null || chat.isHidden(viewer, messageId))
            throw new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "Message unavailable");
        var boundary = new Cursor(target.getCreatedAt(), target.getId());
        var older = window(viewer, roomId, boundary, false, size);
        var newer = window(viewer, roomId, boundary, true, size);
        var items = new ArrayList<Message>(newer.items());
        Collections.reverse(items); // newer window arrives oldest first
        items.add(chat.messageDto(viewer, target));
        items.addAll(older.items());
        return new Context(items, older.nextCursor(), newer.nextCursor());
    }
    public History newer(UUID viewer, UUID roomId, String cursor, int size) {
        size(size); chat.requireRoom(viewer, roomId);
        var boundary = Cursor.read(cursor, "newer:" + roomId);
        if (boundary == null) throw invalid("A newer-message cursor is required");
        var page = window(viewer, roomId, boundary, true, size);
        var items = new ArrayList<>(page.items());
        Collections.reverse(items);
        return new History(items, page.nextCursor());
    }
    private History window(UUID viewer, UUID roomId, Cursor boundary, boolean newer, int size) {
        String op = newer ? ">" : "<", order = newer ? "asc" : "desc";
        // Include tombstones in windows so reconnect can reconcile edits/deletions safely.
        var query = em.createQuery("select m from ChatMessage m where " + ACCESS +
            " and m.roomId=:room and (m.createdAt" + op + ":time or (m.createdAt=:time and m.id" + op + ":id))" +
            " order by m.createdAt " + order + ",m.id " + order, ChatMessage.class)
            .setParameter("viewer", viewer).setParameter("room", roomId)
            .setParameter("time", boundary.time()).setParameter("id", boundary.id()).setMaxResults(size + 1);
        var found = query.getResultList();
        var page = found.subList(0, Math.min(size, found.size()));
        return new History(page.stream().map(m -> chat.messageDto(viewer, m)).toList(), found.size() > size
            ? cursor(newer ? "newer:" + roomId : roomId.toString(), page.get(page.size()-1)) : null);
    }
}
