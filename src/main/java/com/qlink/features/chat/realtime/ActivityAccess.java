package com.qlink.features.chat.realtime;

import com.qlink.features.chat.repository.*;
import com.qlink.features.chat.enums.*;
import com.qlink.features.user.repository.UserRepository;
import com.qlink.features.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityAccess {
    private final UserRepository users;
    private final UserService current;
    private final ChatRoomRepository rooms;
    private final ChatMemberRepository members;
    private final com.qlink.features.user.service.PrivacyPolicy privacy;
    public com.qlink.features.user.service.PrivacyPolicy.Visibility visibility(UUID viewer,UUID target) { return privacy.visibility(viewer,target); }
    public boolean canMessage(UUID a,UUID b) { return privacy.canMessage(a,b); }
    public Instant lastSeen(UUID target) { return users.findById(target).map(u -> u.getLastSeenAt()).orElse(null); }
    @Transactional
    public void persist(UUID user, Instant seen) { users.recordLastSeen(user, seen); }
    public List<UUID> typingRecipients(UUID actor, UUID roomId) {
        current.requireActive(actor);
        var room = rooms.findById(roomId).orElse(null);
        if (room == null) return List.of();
        var list = members.findByRoomId(roomId);
        var mine = list.stream().filter(m -> m.getUserId().equals(actor)).findFirst().orElse(null);
        if (mine == null || (room.getType() != ChatRoomType.PERSONAL && !room.isMembersCanSend()
            && mine.getRole() == ChatRoomMemberRole.MEMBER)) return List.of();
        if (room.getType()==ChatRoomType.PERSONAL && list.stream().anyMatch(m -> !m.getUserId().equals(actor) && !privacy.canMessage(actor,m.getUserId()))) return List.of();
        return list.stream().map(m -> m.getUserId()).filter(id -> !id.equals(actor)).toList();
    }
}
