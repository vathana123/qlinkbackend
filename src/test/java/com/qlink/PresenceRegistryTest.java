package com.qlink;

import com.qlink.features.chat.realtime.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PresenceRegistryTest {
    Jwt token(UUID user) {
        return Jwt.withTokenValue("test-only").header("alg", "RS256").subject(user.toString())
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
    }
    @Test void duplicateConnectDisconnectAndMultipleDevices() {
        var registry = new PresenceRegistry(); var user = UUID.randomUUID(); var jwt = token(user); var now = Instant.now();
        assertThat(registry.connect("first", jwt, now)).isTrue();
        assertThat(registry.connect("first", jwt, now)).isFalse();
        assertThat(registry.connect("second", jwt, now)).isFalse();
        registry.touch("first",now.plusSeconds(5));
        registry.remove("first"); registry.remove("first");
        assertThat(registry.online(user)).isTrue();
        assertThat(registry.remove("second").touched()).isEqualTo(now.plusSeconds(5)); assertThat(registry.online(user)).isFalse();
        assertThat(registry.connect("reconnect", jwt, now)).isTrue();
    }
    @Test void heartbeatTimeoutRevocationAndOnlyFinalDeviceWritesOffline() {
        var registry = new PresenceRegistry(); var user = UUID.randomUUID(); var jwt = token(user);
        var access = mock(ActivityAccess.class); var sockets = mock(SocketAccess.class); var broker = mock(SimpMessagingTemplate.class);
        when(sockets.active(jwt)).thenReturn(true);
        var service = new ActivityService(registry, access, sockets, broker);
        var old = Instant.now().minusSeconds(60);
        registry.connect("lost", jwt, old); registry.connect("active", jwt, Instant.now());
        service.sweep(); assertThat(registry.online(user)).isTrue();
        assertThat(registry.snapshot()).containsOnlyKeys("active");
        clearInvocations(access);
        service.sweep(); verify(access, never()).persist(any(), any());
        when(sockets.active(jwt)).thenReturn(false);
        service.sweep(); assertThat(registry.online(user)).isFalse();
        verify(access).persist(eq(user), any());
        clearInvocations(access); service.sweep(); verifyNoInteractions(access);
    }
    @Test void watchesRedactInaccessiblePresenceAndTypingRequiresRoomAccess() {
        var registry = new PresenceRegistry(); var actor = UUID.randomUUID(); var viewer = UUID.randomUUID(); var room = UUID.randomUUID();
        var access = mock(ActivityAccess.class); var broker = mock(SimpMessagingTemplate.class);
        var service = new ActivityService(registry, access, mock(SocketAccess.class), broker);
        registry.connect("a", token(actor), Instant.now()); registry.connect("v", token(viewer), Instant.now());
        when(access.visibility(viewer, actor)).thenReturn(new com.qlink.features.user.service.PrivacyPolicy.Visibility(false,false));
        assertThat(service.presence(viewer, actor).online()).isNull();
        when(access.visibility(viewer, actor)).thenReturn(new com.qlink.features.user.service.PrivacyPolicy.Visibility(true,true));
        service.watch("v", viewer, List.of(actor));
        verify(broker).convertAndSendToUser(eq(viewer.toString()), eq("/queue/activity"), eq(new ActivityService.Presence("PRESENCE", actor, true, null)));
        clearInvocations(broker);
        service.typing("a", actor, room, true); verifyNoInteractions(broker);
        when(access.typingRecipients(actor, room)).thenReturn(List.of(viewer));
        service.typing("a", actor, room, true); service.typing("a", actor, room, true);
        verify(broker, times(1)).convertAndSendToUser(eq(viewer.toString()), eq("/queue/activity"), any(ActivityService.Typing.class));
        service.typing("a", actor, room, false);
        verify(broker, times(2)).convertAndSendToUser(eq(viewer.toString()), eq("/queue/activity"), any(ActivityService.Typing.class));
    }
}
