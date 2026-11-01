package com.qlink.features.user.service;
import com.qlink.common.exception.ApiException;
import com.qlink.features.user.enums.*;
import com.qlink.features.user.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class PrivacyPolicy {
    public record Visibility(boolean online,boolean lastSeen) {}
    private final UserRepository users;
    private final UserBlockRepository blocks;
    public boolean canMessage(UUID a,UUID b) { return !a.equals(b) && !blocks.between(a,b); }
    public void requireMessaging(UUID a,UUID b) {
        if (!canMessage(a,b)) throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"PERSONAL_MESSAGING_UNAVAILABLE","Personal messaging is unavailable");
    }
    public Visibility visibility(UUID viewer,UUID target) {
        var user=users.findById(target).filter(u -> u.getStatus()==UserStatus.ACTIVE).orElse(null);
        if(user==null || blocks.between(viewer,target)) return new Visibility(false,false);
        if(viewer.equals(target)) return new Visibility(true,true);
        boolean contact=(user.getOnlineVisibility()==PresenceVisibility.CONTACTS || user.getLastSeenVisibility()==PresenceVisibility.CONTACTS)
            && users.contacts(viewer,target,org.springframework.data.domain.PageRequest.of(0,1)).hasContent();
        return new Visibility(allowed(user.getOnlineVisibility(),contact),allowed(user.getLastSeenVisibility(),contact));
    }
    private boolean allowed(PresenceVisibility value,boolean contact) { return value==PresenceVisibility.EVERYONE || (value==PresenceVisibility.CONTACTS && contact); }
}
