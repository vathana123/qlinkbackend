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
 @Test void queuedPresenceIsRecheckedAndRedactedUpdatesStillReachViewer() {
  var decoder=mock(JwtDecoder.class); var access=mock(SocketAccess.class); var activity=mock(ActivityAccess.class);
  var viewer=UUID.randomUUID(); var target=UUID.randomUUID();
  var jwt=Jwt.withTokenValue("test").header("alg","RS256").subject(viewer.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
  when(decoder.decode("test")).thenReturn(jwt); when(access.active(jwt)).thenReturn(true);
  var security=new SocketSecurity(decoder,access,new PresenceRegistry(),activity,new tools.jackson.databind.json.JsonMapper());
  var connect=StompHeaderAccessor.create(StompCommand.CONNECT); connect.setSessionId("s"); connect.setNativeHeader("Authorization","Bearer test"); connect.setLeaveMutable(true);
  security.preSend(MessageBuilder.createMessage(new byte[0],connect.getMessageHeaders()),null);
  var h=SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE); h.setSessionId("s"); h.setDestination("/queue/activity-users");
  var body=("{\"type\":\"PRESENCE\",\"userId\":\""+target+"\",\"online\":true}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
  var message=MessageBuilder.createMessage(body,h.getMessageHeaders());
  when(activity.visibility(viewer,target)).thenReturn(new com.qlink.features.user.service.PrivacyPolicy.Visibility(true,true));
  assertThat(security.outbound().preSend(message,null)).isNotNull();
  when(activity.visibility(viewer,target)).thenReturn(new com.qlink.features.user.service.PrivacyPolicy.Visibility(false,false));
  assertThat(security.outbound().preSend(message,null)).isNull();
  var hidden=("{\"type\":\"PRESENCE\",\"userId\":\""+target+"\",\"online\":null,\"lastSeenAt\":null}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
  assertThat(security.outbound().preSend(MessageBuilder.createMessage(hidden,h.getMessageHeaders()),null)).isNotNull();
 }
 @Test void anonymousSubscribeAndSendDeniedAndImmutableDisconnectIsSafe() {
  var security=new SocketSecurity(mock(JwtDecoder.class),mock(SocketAccess.class),new PresenceRegistry(),mock(ActivityAccess.class),new tools.jackson.databind.json.JsonMapper());
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
  var security=new SocketSecurity(decoder,access,new PresenceRegistry(),mock(ActivityAccess.class),new tools.jackson.databind.json.JsonMapper());
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
