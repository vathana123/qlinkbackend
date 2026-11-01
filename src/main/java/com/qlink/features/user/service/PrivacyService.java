package com.qlink.features.user.service;
import com.qlink.common.exception.ApiException;
import com.qlink.features.user.dto.PublicUserResponse;
import com.qlink.features.user.entity.UserBlock;
import com.qlink.features.user.enums.PresenceVisibility;
import com.qlink.features.user.mapper.UserMapper;
import com.qlink.features.user.repository.*;
import com.qlink.features.chat.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.*;
import jakarta.validation.constraints.NotNull;
import java.util.*;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class PrivacyService {
    public record Settings(@NotNull PresenceVisibility onlineVisibility,@NotNull PresenceVisibility lastSeenVisibility) {}
    public record Relationship(boolean blockedByMe,boolean canMessage) {}
    private final UserService current;
    private final UserRepository users;
    private final UserBlockRepository blocks;
    private final ChatRoomRepository rooms;
    private final PrivacyPolicy policy;
    private final UserMapper mapper;
    private final ApplicationEventPublisher events;
    public Settings settings(UUID viewer) {
        var user=current.requireActive(viewer); return new Settings(user.getOnlineVisibility(),user.getLastSeenVisibility());
    }
    @Transactional
    public Settings update(UUID viewer,Settings settings) {
        users.lockById(viewer).orElseThrow();
        var user=current.requireActive(viewer);
        user.setOnlineVisibility(settings.onlineVisibility()); user.setLastSeenVisibility(settings.lastSeenVisibility());
        users.saveAndFlush(user); events.publishEvent(new PrivacyChanged(viewer,null)); return settings;
    }
    public Relationship relationship(UUID viewer,UUID other) {
        current.requireActive(viewer);
        return new Relationship(blocks.findByBlockerIdAndBlockedId(viewer,other).isPresent(),
            users.findById(other).filter(u -> u.getStatus()==com.qlink.features.user.enums.UserStatus.ACTIVE).isPresent() && policy.canMessage(viewer,other));
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Relationship block(UUID viewer,UUID other,boolean enabled) {
        if(viewer.equals(other)) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_REQUEST","Choose another user");
        var ids=new ArrayList<>(List.of(viewer,other)); ids.sort(Comparator.comparing(UUID::toString));
        for(var id:ids) {
            var locked=users.lockById(id);
            if(locked.isEmpty() && (enabled || id.equals(viewer))) throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"USER_NOT_FOUND","User unavailable");
        }
        current.requireActive(viewer);
        // Same user->room lock order as sending; serialize reactions/edits too.
        rooms.findByPersonalPairKey(com.qlink.features.chat.service.ChatService.pairKey(viewer,other)).ifPresent(r -> rooms.lockById(r.getId()));
        var previous=blocks.findByBlockerIdAndBlockedId(viewer,other);
        if(enabled && previous.isEmpty()) { var block=new UserBlock(); block.setBlockerId(viewer); block.setBlockedId(other); blocks.saveAndFlush(block); }
        if(!enabled && previous.isPresent()) { blocks.delete(previous.get()); blocks.flush(); }
        events.publishEvent(new PrivacyChanged(viewer,other)); return relationship(viewer,other);
    }
    public Page<PublicUserResponse> blocked(UUID viewer,int page,int size) {
        current.requireActive(viewer);
        if(page<0 || page>10000 || size<1 || size>50) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_REQUEST","Invalid pagination");
        return blocks.blocked(viewer,PageRequest.of(page,size)).map(b -> users.findById(b.getBlockedId()).map(mapper::toPublic)
            .orElse(new PublicUserResponse(b.getBlockedId(),null,"Unavailable user",null)));
    }
}
