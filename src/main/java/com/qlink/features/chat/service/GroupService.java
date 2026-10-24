package com.qlink.features.chat.service;

import com.qlink.common.exception.ApiException;
import com.qlink.features.chat.dto.ChatDtos.*;
import com.qlink.features.chat.entity.*;
import com.qlink.features.chat.enums.*;
import com.qlink.features.chat.repository.*;
import com.qlink.features.chat.realtime.GroupChanged;
import com.qlink.features.user.service.UserService;
import com.qlink.features.storage.repository.StoredFileRepository;
import com.qlink.features.storage.service.VoiceFiles;
import com.qlink.features.storage.enums.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.util.*;

/** All group mutations lock the room before reading membership or permissions. */
@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class GroupService {
    private final ChatRoomRepository rooms;
    private final ChatMemberRepository members;
    private final UserService users;
    private final ChatService chat;
    private final StoredFileRepository files;
    private final VoiceFiles attachments;
    private final ApplicationEventPublisher events;

    public GroupService(ChatRoomRepository rooms, ChatMemberRepository members, UserService users,
            ChatService chat, StoredFileRepository files, VoiceFiles attachments, ApplicationEventPublisher events) {
        this.rooms=rooms; this.members=members; this.users=users; this.chat=chat;
        this.files=files; this.attachments=attachments; this.events=events;
    }
    private ApiException denied() { return new ApiException(HttpStatus.FORBIDDEN,"GROUP_PERMISSION_DENIED","This group action is not permitted"); }
    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_GROUP_ACTION",message); }
    private ChatRoom lock(UUID actor, UUID id) {
        users.requireActive(actor);
        var room=rooms.lockById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"ROOM_NOT_FOUND","Group unavailable"));
        if(room.getType()==ChatRoomType.PERSONAL) throw invalid("A group is required");
        member(actor,id);
        return room;
    }
    private ChatRoomMember member(UUID user, UUID room) {
        return members.findByRoomId(room).stream().filter(m -> m.getUserId().equals(user)).findFirst().orElseThrow(this::denied);
    }
    private void owner(UUID actor, UUID room) {
        if(member(actor,room).getRole()!=ChatRoomMemberRole.OWNER) throw denied();
    }
    private void manager(UUID actor, UUID room) {
        if(member(actor,room).getRole()==ChatRoomMemberRole.MEMBER) throw denied();
    }
    private Room changed(UUID actor, ChatRoom room) {
        members.flush(); rooms.flush();
        events.publishEvent(new GroupChanged(room.getId()));
        return chat.group(actor,room.getId());
    }
    public Room update(UUID actor, UUID id, GroupUpdate request) {
        var room=lock(actor,id); manager(actor,id);
        room.setGroupName(request.name().strip());
        if(room.getGroupName().isEmpty()) throw invalid("Group name must not be blank");
        room.setDescription(request.description()==null?null:request.description().strip());
        return changed(actor,room);
    }
    public Room permissions(UUID actor, UUID id, GroupPermissions request) {
        var room=lock(actor,id); owner(actor,id);
        room.setMembersCanSend(request.membersCanSend()); room.setMembersCanInvite(request.membersCanInvite());
        return changed(actor,room);
    }
    public Room add(UUID actor, UUID id, AddMembers request) {
        var room=lock(actor,id);
        if(member(actor,id).getRole()==ChatRoomMemberRole.MEMBER && !room.isMembersCanInvite()) throw denied();
        var existing=members.findByRoomId(id);
        var ids=new HashSet<>(request.memberIds());
        if(ids.size()!=request.memberIds().size()) throw invalid("Select distinct users");
        ids.removeAll(existing.stream().map(ChatRoomMember::getUserId).toList());
        if(existing.size()+ids.size()>200) throw invalid("Groups support at most 200 members");
        for(UUID user:ids) {
            users.requireActive(user);
            var membership=new ChatRoomMember(); membership.setRoomId(id); membership.setUserId(user);
            membership.setRole(ChatRoomMemberRole.MEMBER);
            // Rejoining creates a new period; never reactivate the old membership.
            membership.setJoinedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            members.save(membership);
        }
        return changed(actor,room);
    }
    public void remove(UUID actor, UUID id, UUID target) {
        lock(actor,id); manager(actor,id);
        var targetMember=member(target,id);
        if(targetMember.getRole()==ChatRoomMemberRole.OWNER ||
                (member(actor,id).getRole()==ChatRoomMemberRole.CO_OWNER && targetMember.getRole()!=ChatRoomMemberRole.MEMBER)) throw denied();
        if(actor.equals(target)) throw invalid("Use Leave group");
        targetMember.softDelete(actor); members.flush();
        events.publishEvent(new GroupChanged(id,Set.of(target),null));
    }
    public void leave(UUID actor, UUID id) {
        lock(actor,id);
        var membership=member(actor,id);
        if(membership.getRole()==ChatRoomMemberRole.OWNER) throw invalid("Transfer ownership before leaving, or delete the group");
        membership.softDelete(actor); members.flush();
        events.publishEvent(new GroupChanged(id,Set.of(actor),null));
    }
    public Room role(UUID actor, UUID id, UUID target, ChangeRole request) {
        var room=lock(actor,id); owner(actor,id);
        var membership=member(target,id);
        if(membership.getRole()==ChatRoomMemberRole.OWNER || request.role()==ChatRoomMemberRole.OWNER) throw invalid("Use Transfer ownership");
        membership.setRole(request.role());
        return changed(actor,room);
    }
    public Room transfer(UUID actor, UUID id, TransferOwner request) {
        var room=lock(actor,id); owner(actor,id);
        if(actor.equals(request.userId())) throw invalid("Select another member");
        var next=member(request.userId(),id); users.requireActive(next.getUserId());
        member(actor,id).setRole(ChatRoomMemberRole.CO_OWNER);
        members.flush(); // Release unique active_owner before promoting; still one atomic transaction.
        next.setRole(ChatRoomMemberRole.OWNER);
        return changed(actor,room);
    }
    public Room avatar(UUID actor, UUID id, UUID fileId) {
        var room=lock(actor,id); manager(actor,id);
        if(Objects.equals(room.getAvatarFileId(),fileId)) return chat.group(actor,id);
        if(fileId!=null) {
            var file=files.lockIncludingDeleted(fileId).orElseThrow(() -> invalid("Image unavailable"));
            if(!actor.equals(file.getUploadedBy()) || file.getDeletedAt()!=null || file.getStatus()!=StoredFileStatus.AVAILABLE || file.getCategory()!=FileCategory.IMAGE)
                throw invalid("Upload an available image owned by you");
            attachments.requireUnattached(fileId);
        }
        UUID old=room.getAvatarFileId();
        room.setAvatarFileId(fileId); rooms.flush();
        retireAvatar(actor,old);
        events.publishEvent(new GroupChanged(id,Set.of(),old));
        return chat.group(actor,id);
    }
    private void retireAvatar(UUID actor, UUID id) {
        if(id==null) return;
        var file=files.lockIncludingDeleted(id).orElseThrow();
        file.markDeleted(actor); files.flush();
    }
    public void delete(UUID actor, UUID id) {
        var room=lock(actor,id); owner(actor,id);
        var all=members.findByRoomId(id);
        Set<UUID> recipients=new HashSet<>();
        for(var membership:all) { recipients.add(membership.getUserId()); membership.softDelete(actor); }
        room.softDelete(actor); retireAvatar(actor,room.getAvatarFileId());
        members.flush(); rooms.flush();
        events.publishEvent(new GroupChanged(id,Set.copyOf(recipients),room.getAvatarFileId()));
    }
}
