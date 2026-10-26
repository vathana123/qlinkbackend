package com.qlink.features.chat.realtime;
import lombok.RequiredArgsConstructor;
import com.qlink.features.chat.repository.*;
import com.qlink.features.chat.enums.*;
import com.qlink.features.chat.service.ChatService;
import com.qlink.features.user.repository.UserRepository;
import com.qlink.features.user.enums.UserStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.slf4j.*;
@Service
@RequiredArgsConstructor
public class ChatEventDelivery {
 private static final Logger log=LoggerFactory.getLogger(ChatEventDelivery.class);
 private final ChatMessageRepository messages; private final ChatRoomRepository rooms;
 private final ChatMemberRepository members; private final UserRepository users;
 private final ChatService chat; private final SimpMessagingTemplate broker;

 @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
 public void deliver(MessageCommitted committed) {
  var message=messages.findById(committed.messageId()).orElse(null);
  if(message==null)return;
  var room=rooms.findById(message.getRoomId()).orElse(null);
  if(room==null)return;

  boolean failed=false;
  for(var member:members.findByRoomId(room.getId())) {
   if(users.findById(member.getUserId()).filter(u -> u.getStatus()==UserStatus.ACTIVE).isEmpty())continue;
   if(room.getType()!=ChatRoomType.PERSONAL && room.getMessageHistoryVisibility()==MessageHistoryVisibility.VIEW_AFTER_JOIN &&
     message.getCreatedAt().isBefore(member.getJoinedAt()))continue;
   if(committed.onlyUser()!=null && !committed.onlyUser().equals(member.getUserId())) continue;
   try {
    var event=new ChatEvent("MESSAGE_CREATED".equals(committed.type())?message.getId():committed.jobId()==null?java.util.UUID.randomUUID():committed.jobId(),committed.type(),room.getId(),room.getType(),chat.messageDto(member.getUserId(),message),chat.room(member.getUserId(),room.getId()));
    broker.convertAndSendToUser(member.getUserId().toString(),"/queue/chat-events",event);
   } catch(RuntimeException ex) {
    failed=true;
    log.warn("Chat event delivery failed for message {} ({})",message.getId(),ex.getClass().getSimpleName());
   }
  }
  if(failed) throw new IllegalStateException("Chat event delivery incomplete");
 }
 @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
 public void deliver(GroupChanged changed) {
  var room=rooms.findById(changed.roomId()).orElse(null);
  var recipients=new java.util.HashSet<>(changed.removedUsers());
  if(room!=null) members.findByRoomId(room.getId()).forEach(m -> recipients.add(m.getUserId()));
  for(var user:recipients) {
   if(users.findById(user).filter(u -> u.getStatus()==UserStatus.ACTIVE).isEmpty()) continue;
   try {
    // Re-read membership after commit. A delayed removal event must not evict a rejoined member.
    boolean active=room!=null && members.findByRoomId(room.getId()).stream().anyMatch(m -> m.getUserId().equals(user));
    var event=new ChatEvent(java.util.UUID.randomUUID(),active?"GROUP_UPDATED":"GROUP_REMOVED",changed.roomId(),
     room==null?ChatRoomType.GROUP_PRIVATE:room.getType(),null,active?chat.group(user,room.getId()):null);
    broker.convertAndSendToUser(user.toString(),"/queue/chat-events",event);
   } catch(RuntimeException ex) { log.warn("Group event delivery failed ({})",ex.getClass().getSimpleName()); }
  }
 }
}
