package com.qlink.features.chat.realtime;
import com.qlink.features.auth.service.SessionAuthorization;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class SocketAccess {
 private final SessionAuthorization sessions;
 public SocketAccess(SessionAuthorization sessions) {this.sessions=sessions;}
 @Transactional(readOnly=true)
 public boolean active(Jwt jwt) {
  try {
   sessions.recheck(jwt);
   return true;
  } catch(RuntimeException ex) {return false;}
 }
}
