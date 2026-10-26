package com.qlink.features.chat.dto;
import com.qlink.features.user.dto.PublicUserResponse;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
public final class ChatDtos {
 private ChatDtos() {}
 public record PersonalSend(@NotNull UUID recipientId,@NotNull UUID clientMessageId,@Size(max=4000) String content, String type, UUID fileId, Long durationMs, UUID replyToId) {
 public PersonalSend(UUID recipientId, UUID clientMessageId, String content) { this(recipientId,clientMessageId,content,null,null,null,null); }
 }
 public record Send(@NotNull UUID clientMessageId,@Size(max=4000) String content, String type, UUID fileId, Long durationMs, UUID replyToId) {
 public Send(UUID clientMessageId, String content) { this(clientMessageId,content,null,null,null,null); }
 }
 public record Receipts(@NotEmpty @Size(max=50) List<@NotNull UUID> messageIds, boolean seen) {}
 public record Edit(@NotBlank @Size(max=4000) String content, @NotNull @PositiveOrZero Long revision) {}
 public record Reply(UUID id, String senderDisplayName, String type, String content, boolean unavailable) {}
 public record Message(UUID id, UUID roomId, UUID senderId, UUID clientMessageId, String type, String content, Instant createdAt, String senderDisplayName, UUID fileId, Long durationMs, String audioContentType, Long audioSizeBytes, Attachment attachment, UUID replyToId, Reply reply, Instant editedAt, Instant removedAt, long revision, boolean hidden, boolean canDeleteForEveryone, String delivery, int recipientCount, int deliveredCount, int seenCount) {}
 public record Attachment(String originalName, String contentType, long sizeBytes) {}
 public record Room(UUID id, UUID creatorId, PublicUserResponse participant, Instant lastMessageAt, Message lastMessage, String type, String name, String avatarUrl, int memberCount, String messageHistoryVisibility,
 String description, String myRole, boolean membersCanSend, boolean membersCanInvite) {}
 public record GroupUpdate(@NotBlank @Size(max=100) String name, @Size(max=2000) String description) {}
 public record GroupPermissions(boolean membersCanSend, boolean membersCanInvite) {}
 public record AddMembers(@NotEmpty @Size(max=199) List<@NotNull UUID> memberIds) {}
 public record ChangeRole(@NotNull com.qlink.features.chat.enums.ChatRoomMemberRole role) {}
 public record TransferOwner(@NotNull UUID userId) {}
 public record GroupAvatar(@NotNull UUID fileId) {}
 public record CreateGroup(String name, com.qlink.features.chat.enums.ChatRoomType type,
 com.qlink.features.chat.enums.MessageHistoryVisibility messageHistoryVisibility, List<UUID> memberIds) {}
 public record GroupMember(UUID id, String displayName, String username, String avatarUrl, String role, Instant joinedAt) {}
 public record Lookup(boolean exists, Room room) {}
 public record Sent(Room room, Message message) {}
 public record History(List<Message> items, String nextCursor) {}
}
