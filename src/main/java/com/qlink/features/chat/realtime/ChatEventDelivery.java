package com.qlink.features.chat.realtime;
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
public class ChatEventDelivery {
 private static final Logger log=LoggerFactory.getLogger(ChatEventDelivery.class);
 private final ChatMessageRepository messages; private final ChatRoomRepository rooms;
 private final ChatMemberRepository members; private final UserRepository users;
 private final ChatService chat; private final SimpMessagingTemplate broker;
 public ChatEventDelivery(ChatMessageRepository messages,ChatRoomRepository rooms,ChatMemberRepository members,
  UserRepository users,ChatService chat,SimpMessagingTemplate broker) {
  this.messages=messages;this.rooms=rooms;this.members=members;this.users=users;this.chat=chat;this.broker=broker;
 }
 @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
 public void deliver(MessageCommitted committed) {
  var message=messages.findById(committed.messageId()).orElse(null);
  if(message==null)return;
  var room=rooms.findById(message.getRoomId()).orElse(null);
  if(room==null)return;
  var dto=chat.messageDto(message);
  for(var member:members.findByRoomId(room.getId())) {
   if(users.findById(member.getUserId()).filter(u -> u.getStatus()==UserStatus.ACTIVE).isEmpty())continue;
   if(room.getType()!=ChatRoomType.PERSONAL && room.getMessageHistoryVisibility()==MessageHistoryVisibility.VIEW_AFTER_JOIN &&
     message.getCreatedAt().isBefore(member.getJoinedAt()))continue;
   try {
    var event=new ChatEvent(message.getId(),"MESSAGE_CREATED",room.getId(),room.getType(),dto,chat.room(member.getUserId(),room.getId()));
    broker.convertAndSendToUser(member.getUserId().toString(),"/queue/chat-events",event);
   } catch(RuntimeException ex) {
    log.warn("Chat event delivery failed for message {} ({})",message.getId(),ex.getClass().getSimpleName());
   }
  }
 }
}
