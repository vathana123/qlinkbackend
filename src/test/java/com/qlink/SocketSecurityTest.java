package com.qlink;
import com.qlink.features.chat.realtime.*;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.*;
import org.springframework.messaging.support.*;
import org.springframework.messaging.simp.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.security.oauth2.jwt.*;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SocketSecurityTest {
 @Test void anonymousSubscribeAndSendDeniedAndImmutableDisconnectIsSafe() {
  var security=new SocketSecurity(mock(JwtDecoder.class),mock(SocketAccess.class));
  for(var command:new StompCommand[]{StompCommand.SUBSCRIBE,StompCommand.SEND}) {
   var h=StompHeaderAccessor.create(command);h.setSessionId("anonymous");h.setDestination("/user/queue/chat-events");
   assertThatThrownBy(() -> security.preSend(MessageBuilder.createMessage(new byte[0],h.getMessageHeaders()),null))
    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }
  var h=StompHeaderAccessor.create(StompCommand.DISCONNECT);h.setSessionId("anonymous");
  var message=MessageBuilder.createMessage(new byte[0],h.getMessageHeaders());
  assertThatCode(() -> security.preSend(message,null)).doesNotThrowAnyException();
 }
 @Test void authenticationPrecedesAuthorizationAndOutboundRechecksRevocation() {
  var decoder=mock(JwtDecoder.class);var access=mock(SocketAccess.class);
  var jwt=Jwt.withTokenValue("secret").header("alg","RS256").subject(UUID.randomUUID().toString())
    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
  when(decoder.decode("secret")).thenReturn(jwt);when(access.active(jwt)).thenReturn(true);
  var security=new SocketSecurity(decoder,access);
  var connect=StompHeaderAccessor.create(StompCommand.CONNECT);connect.setSessionId("session");
  connect.setNativeHeader("Authorization","Bearer secret");connect.setLeaveMutable(true);
  security.preSend(MessageBuilder.createMessage(new byte[0],connect.getMessageHeaders()),null);
  assertThat(connect.getUser().getName()).isEqualTo(jwt.getSubject());
  assertThat(connect.getNativeHeader("Authorization")).isNull();
  var subscribe=StompHeaderAccessor.create(StompCommand.SUBSCRIBE);subscribe.setSessionId("session");
  subscribe.setUser(connect.getUser());subscribe.setDestination("/user/queue/chat-events");
  assertThatCode(() -> security.preSend(MessageBuilder.createMessage(new byte[0],subscribe.getMessageHeaders()),null)).doesNotThrowAnyException();
  var outbound=SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);outbound.setSessionId("session");
  var message=MessageBuilder.createMessage(new byte[0],outbound.getMessageHeaders());
  assertThat(security.outbound().preSend(message,null)).isNotNull();
  when(access.active(jwt)).thenReturn(false);
  assertThat(security.outbound().preSend(message,null)).isNull();
  assertThatThrownBy(() -> security.preSend(MessageBuilder.createMessage(new byte[0],subscribe.getMessageHeaders()),null))
    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
 }
}
