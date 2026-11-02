package com.qlink.features.chat.service;

import com.qlink.features.chat.dto.ChatDtos.History;
import com.qlink.features.chat.entity.ChatMessage;
import com.qlink.features.chat.enums.MessageType;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatMediaService {
    private final ChatService chat;
    private final EntityManager em;

    public History list(UUID viewer, UUID roomId, String category, String cursor, int size) {
        var room = chat.requireRoom(viewer, roomId);
        if (size < 1 || size > 50) throw ChatSearchService.invalid("Page size must be 1–50");
        MessageType type = switch (category) {
            case "PHOTOS" -> MessageType.IMAGE;
            case "VOICE" -> MessageType.VOICE;
            case "FILES" -> MessageType.FILE;
            default -> throw ChatSearchService.invalid("Choose PHOTOS, VOICE or FILES");
        };
        String scope = "media:" + roomId + ":" + category;
        var boundary = ChatSearchService.Cursor.read(cursor, scope);
        var since = chat.historyStart(viewer, room);
        String query = """
            select m from ChatMessage m where m.roomId=:room and m.type=:type
            and m.removedAt is null and m.fileId is not null
            and not exists(select h.id from MessageHidden h where h.messageId=m.id and h.userId=:viewer)
            and exists(select f.id from StoredFile f where f.id=m.fileId
                and f.status=com.qlink.features.storage.enums.StoredFileStatus.AVAILABLE)
            """;
        if (since != null) query += " and m.createdAt>=:since";
        if (boundary != null) query += " and (m.createdAt<:time or (m.createdAt=:time and m.id<:id))";
        var q = em.createQuery(query + " order by m.createdAt desc,m.id desc", ChatMessage.class)
            .setParameter("room", roomId).setParameter("viewer", viewer).setParameter("type", type).setMaxResults(size + 1);
        if (since != null) q.setParameter("since", since);
        if (boundary != null) q.setParameter("time", boundary.time()).setParameter("id", boundary.id());
        var found = q.getResultList();
        var items = found.subList(0, Math.min(size, found.size()));
        var last = items.isEmpty() ? null : items.get(items.size()-1);
        // Return message references, not copies of stored files. Blocking retains existing history.
        return new History(items.stream().map(m -> chat.messageDto(viewer, m)).toList(),
            found.size() > size ? new ChatSearchService.Cursor(last.getCreatedAt(), last.getId()).encode(scope) : null);
    }
}
