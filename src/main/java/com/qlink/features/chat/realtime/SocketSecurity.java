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
 private final Map<String,Jwt> sessions=new ConcurrentHashMap<>();
 public SocketSecurity(@Qualifier("accessJwtDecoder") JwtDecoder decoder,SocketAccess access) {this.decoder=decoder;this.access=access;}
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
  if(command==null) return message;
  if(command==StompCommand.UNSUBSCRIBE) return message;
  if(command==StompCommand.SUBSCRIBE && "/user/queue/chat-events".equals(h.getDestination()) && access.active(jwt)) return message;
  // No client SEND, ACK, transactions, or arbitrary subscriptions.
  throw denied();
 }
 public void forget(String session) {if(session!=null)sessions.remove(session);}
 public ChannelInterceptor outbound() {
  return new ChannelInterceptor() {
   @Override public Message<?> preSend(Message<?> message,MessageChannel channel) {
    var h=StompHeaderAccessor.wrap(message);
    if(h.getMessageType()!=org.springframework.messaging.simp.SimpMessageType.MESSAGE) return message;
    var jwt=h.getSessionId()==null?null:sessions.get(h.getSessionId());
    return jwt!=null && access.active(jwt)?message:null;
   }
  };
 }
 @org.springframework.context.event.EventListener
 public void disconnected(org.springframework.web.socket.messaging.SessionDisconnectEvent event) {forget(event.getSessionId());}
}
