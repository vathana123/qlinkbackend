package com.qlink.features.chat.realtime;
import java.util.Set;
import java.util.UUID;
public record GroupChanged(UUID roomId, Set<UUID> removedUsers, UUID retiredAvatar) {
 public GroupChanged(UUID roomId) { this(roomId, Set.of(), null); }
}
