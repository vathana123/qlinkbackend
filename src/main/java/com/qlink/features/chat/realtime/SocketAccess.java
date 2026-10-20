package com.qlink.features.chat.realtime;
import com.qlink.features.auth.repository.AuthSessionRepository;
import com.qlink.features.user.service.UserService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;
@Service
public class SocketAccess {
 private final AuthSessionRepository sessions; private final UserService users;
 public SocketAccess(AuthSessionRepository sessions,UserService users) {this.sessions=sessions;this.users=users;}
 @Transactional(readOnly=true)
 public boolean active(Jwt jwt) {
  try {
   if(jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(Instant.now())) return false;
   UUID user=UUID.fromString(jwt.getSubject()); users.requireActive(user);
   return sessions.findById(UUID.fromString(jwt.getClaimAsString("sid")))
    .filter(s -> s.getUserId().equals(user) && s.getRevokedAt()==null && s.getExpiresAt().isAfter(Instant.now())).isPresent();
  } catch(RuntimeException ex) {return false;}
 }
}
