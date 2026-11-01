package com.qlink.features.chat.realtime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.*;
import org.springframework.messaging.support.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
@Component
public class SocketSecurity implements ChannelInterceptor {
 private final JwtDecoder decoder; private final SocketAccess access;
 private final PresenceRegistry presence;
 private final ActivityAccess activity;
 private final tools.jackson.databind.json.JsonMapper json;
 private final Map<String,Jwt> sessions=new ConcurrentHashMap<>();
 private final Map<String,Map<String,Long>> activityLimits=new ConcurrentHashMap<>();
 public SocketSecurity(@Qualifier("accessJwtDecoder") JwtDecoder decoder,SocketAccess access,PresenceRegistry presence,ActivityAccess activity,tools.jackson.databind.json.JsonMapper json) {this.decoder=decoder;this.access=access;this.presence=presence;this.activity=activity;this.json=json;}
 private AccessDeniedException denied() {return new AccessDeniedException("Connection not authorized");}
 @Override public Message<?> preSend(Message<?> message,MessageChannel channel) {
  var h=MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
  if(h==null) throw denied();
  var command=h.getCommand();
  if(command==StompCommand.DISCONNECT) {forget(h.getSessionId());return message;}
  var authorization=h.getNativeHeader("Authorization");
  if(authorization!=null) h.removeNativeHeader("Authorization");
  if(h.getSessionId()==null) throw denied();
  if(command==StompCommand.CONNECT) {
   try {
    var headers=authorization;
    if(headers==null || headers.size()!=1 || !headers.get(0).startsWith("Bearer ") || sessions.containsKey(h.getSessionId())) throw denied();
    Jwt jwt=decoder.decode(headers.get(0).substring(7));
    if(!access.active(jwt)) throw denied();
    h.setUser(new JwtAuthenticationToken(jwt,List.of(),jwt.getSubject()));
    sessions.put(h.getSessionId(),jwt); return message;
   } catch(RuntimeException ex) {throw denied();}
  }
  Jwt jwt=sessions.get(h.getSessionId());
  if(jwt==null || h.getUser()==null || !h.getUser().getName().equals(jwt.getSubject())) throw denied();
  presence.touch(h.getSessionId(),java.time.Instant.now());
  if(command==null) return message;
  if(command==StompCommand.UNSUBSCRIBE) return message;
  if(command==StompCommand.SUBSCRIBE && Set.of("/user/queue/chat-events","/user/queue/activity").contains(h.getDestination()) && access.active(jwt)) return message;
  if(command==StompCommand.SEND && Set.of("/app/activity/watch","/app/activity/typing").contains(h.getDestination()) && access.active(jwt)) {
   var limits=activityLimits.computeIfAbsent(h.getSessionId(),id -> new HashMap<>());
   synchronized(limits) {
    long now=System.nanoTime();
    if(now-limits.getOrDefault(h.getDestination(),0L)<200_000_000L) return null;
    limits.put(h.getDestination(),now);
   }
   return message;
  }
  // No client SEND, ACK, transactions, or arbitrary subscriptions.
  throw denied();
 }
 public void forget(String session) {if(session!=null){sessions.remove(session);activityLimits.remove(session);}}
 public ChannelInterceptor outbound() {
  return new ChannelInterceptor() {
   @Override public Message<?> preSend(Message<?> message,MessageChannel channel) {
    var h=StompHeaderAccessor.wrap(message);
    if(h.getMessageType()!=org.springframework.messaging.simp.SimpMessageType.MESSAGE) return message;
    var jwt=h.getSessionId()==null?null:sessions.get(h.getSessionId());
    if(jwt==null || !access.active(jwt)) return null;
    // Check queued ephemeral events at the transport boundary too: permission may
    // have changed since the event was enqueued on the broker's executor.
    if(h.getDestination()!=null && h.getDestination().startsWith("/queue/activity")) {
     try {
      var payload=message.getPayload();
      var data=payload instanceof byte[] bytes?json.readTree(bytes):json.readTree((String)payload);
      var viewer=UUID.fromString(jwt.getSubject()); var actor=UUID.fromString(data.get("userId").asText());
      switch(data.get("type").asText()) {
       case "PRESENCE" -> {
        var allowed=activity.visibility(viewer,actor);
        if((data.hasNonNull("online") && !allowed.online()) || (data.hasNonNull("lastSeenAt") && !allowed.lastSeen())) return null;
       }
       case "TYPING" -> { if(!activity.typingRecipients(actor,UUID.fromString(data.get("roomId").asText())).contains(viewer)) return null; }
       case "PERMISSIONS" -> { if(data.get("canMessage").asBoolean()!=activity.canMessage(viewer,actor)) return null; }
       default -> { return null; }
      }
     } catch(RuntimeException ex) { return null; }
    }
    return message;
   }
  };
 }
 @org.springframework.context.event.EventListener
 public void disconnected(org.springframework.web.socket.messaging.SessionDisconnectEvent event) {forget(event.getSessionId());}
}
