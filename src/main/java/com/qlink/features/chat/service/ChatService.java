package com.qlink.features.chat.service;

import com.qlink.common.exception.ApiException;
import com.qlink.features.chat.dto.ChatDtos.*;
import com.qlink.features.chat.entity.*;
import com.qlink.features.chat.enums.*;
import com.qlink.features.chat.mapper.ChatMapper;
import com.qlink.features.chat.repository.*;
import com.qlink.features.user.dto.PublicUserResponse;
import com.qlink.features.user.mapper.UserMapper;
import com.qlink.features.user.repository.UserRepository;
import com.qlink.features.user.service.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@Transactional(readOnly=true)
public class ChatService {
 @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
 @org.springframework.beans.factory.annotation.Autowired private MessageHiddenRepository hidden;
 @org.springframework.beans.factory.annotation.Autowired private MessageReceiptRepository receipts;
 @org.springframework.beans.factory.annotation.Autowired private ChatEventJobRepository eventJobs;
 @org.springframework.beans.factory.annotation.Autowired private com.qlink.features.push.PushService push;
 private final ChatRoomRepository rooms; private final ChatMemberRepository members;
 private final ChatMessageRepository messages; private final UserRepository users;
 private final UserService current; private final DiscoveryService discovery;
 private final UserMapper userMapper; private final ChatMapper mapper;
 private final TransactionTemplate writes;
 private final com.qlink.features.storage.service.VoiceFiles voiceFiles;
 private final org.springframework.context.ApplicationEventPublisher events;
 public ChatService(ChatRoomRepository rooms, ChatMemberRepository members, ChatMessageRepository messages,
     UserRepository users, UserService current, DiscoveryService discovery, UserMapper userMapper,
     ChatMapper mapper, com.qlink.features.storage.service.VoiceFiles voiceFiles, PlatformTransactionManager transactions, org.springframework.context.ApplicationEventPublisher events) {
  this.events=events; this.voiceFiles=voiceFiles;
  this.rooms=rooms; this.members=members; this.messages=messages; this.users=users;
  this.current=current; this.discovery=discovery; this.userMapper=userMapper; this.mapper=mapper;
  writes=new TransactionTemplate(transactions);
  writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
 }
 public static String pairKey(UUID a, UUID b) {
  return a.toString().compareTo(b.toString()) < 0 ? a+":"+b : b+":"+a;
 }
 private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",message); }
 private static ApiException conflict() { return new ApiException(HttpStatus.CONFLICT,"MESSAGE_CONFLICT","Message identifier already used for a different message"); }
 private static void page(int page,int size) {
  if(page<0 || page>10000 || size<1 || size>50) throw bad("Invalid pagination");
 }
 private ChatRoom requireRoom(UUID viewer,UUID roomId) {
  current.requireActive(viewer);
  ChatRoom room=rooms.findById(roomId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"ROOM_NOT_FOUND","Room unavailable"));
  membership(viewer,roomId);
  if(room.getType()==ChatRoomType.PERSONAL) partner(viewer,roomId); return room;
 }
 private UUID partner(UUID viewer,UUID roomId) {
  var list=members.findByRoomId(roomId);
  if(list.size()!=2 || list.stream().noneMatch(m -> m.getUserId().equals(viewer)) ||
     list.get(0).getUserId().equals(list.get(1).getUserId()))
   throw new ApiException(HttpStatus.FORBIDDEN,"ROOM_ACCESS_DENIED","Active room membership required");
  return list.stream().map(ChatRoomMember::getUserId).filter(id -> !id.equals(viewer)).findFirst().orElseThrow();
 }
 private ChatRoomMember membership(UUID viewer,UUID roomId) {
  return members.findByRoomId(roomId).stream().filter(m -> m.getUserId().equals(viewer)).findFirst()
   .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,"ROOM_ACCESS_DENIED","Active room membership required"));
 }
 private Instant historyStart(UUID viewer,ChatRoom room) {
  // Use only the active membership period. Future rejoin must create a fresh period.
  return room.getType()!=ChatRoomType.PERSONAL && room.getMessageHistoryVisibility()==MessageHistoryVisibility.VIEW_AFTER_JOIN
   ? membership(viewer,room.getId()).getJoinedAt() : null;
 }
 public Message messageDto(ChatMessage m) { return messageDto(m.getSenderId(),m); }
 public boolean isHidden(UUID viewer,UUID id) { return hidden.existsByMessageIdAndUserId(id,viewer); }
 private boolean canDelete(UUID viewer,ChatMessage m) {
  if(m.getSenderId().equals(viewer)) return true;
  var room=rooms.findById(m.getRoomId()).orElseThrow();
  if(room.getType()==ChatRoomType.PERSONAL) return false;
  var role=membership(viewer,room.getId()).getRole();
  var target=members.findByRoomId(room.getId()).stream().filter(x -> x.getUserId().equals(m.getSenderId())).findFirst();
  return role==ChatRoomMemberRole.OWNER || (role==ChatRoomMemberRole.CO_OWNER &&
    (target.isEmpty() || target.get().getRole()==ChatRoomMemberRole.MEMBER));
 }
 public Message messageDto(UUID viewer,ChatMessage m) {
  boolean hide=isHidden(viewer,m.getId()), removed=m.getRemovedAt()!=null || m.getDeletedAt()!=null;
  Reply reply=null;
  if(m.getReplyToId()!=null && !hide && !removed) {
   var original=messages.findById(m.getReplyToId()).orElse(null);
   var room=rooms.findById(m.getRoomId()).orElseThrow();
   var since=historyStart(viewer,room);
   boolean unavailable=original==null || !original.getRoomId().equals(m.getRoomId()) || original.getRemovedAt()!=null ||
    isHidden(viewer,original.getId()) || (since!=null && original.getCreatedAt().isBefore(since));
   reply=unavailable ? new Reply(m.getReplyToId(),null,null,"Message unavailable",true) :
    new Reply(original.getId(),users.findById(original.getSenderId()).map(u -> u.getDisplayName()).orElse("Unavailable user"),
     original.getType().name(),original.getType()==MessageType.TEXT?original.getContent():original.getType().name(),false);
  }
  return new Message(m.getId(),m.getRoomId(),m.getSenderId(),m.getClientMessageId(),m.getType().name(),hide||removed?"":m.getContent(),m.getCreatedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
   users.findById(m.getSenderId()).map(u -> u.getDisplayName()).orElse("Unavailable user"),hide||removed?null:m.getFileId(),hide||removed?null:m.getDurationMs(),hide||removed?null:m.getAudioContentType(),hide||removed?null:m.getAudioSizeBytes(),hide||removed?null:voiceFiles.describe(m),
   m.getReplyToId(),reply,m.getEditedAt(),m.getRemovedAt()==null?m.getDeletedAt():m.getRemovedAt(),m.getRevision(),hide,!hide&&!removed&&canDelete(viewer,m),delivery(m),m.getRecipientCount(),m.getDeliveredCount(),m.getSeenCount());
 }
 private void publishMessage(UUID id,String type,UUID onlyUser) {
  if("MESSAGE_CREATED".equals(type)) push.enqueue(messages.findById(id).orElseThrow());
  var job=new ChatEventJob();job.setMessageId(id);job.setEventType(type);job.setOnlyUser(onlyUser);job.setNextAttemptAt(Instant.now());eventJobs.saveAndFlush(job);
  events.publishEvent(new com.qlink.features.chat.realtime.MessageCommitted(id,type,onlyUser,job.getId()));
 }
 public Sent sentByClient(UUID viewer,UUID key) {
  current.requireActive(viewer);
  var message=messages.previous(viewer,key).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"MESSAGE_NOT_FOUND","Message not committed"));
  var room=requireRoom(viewer,message.getRoomId());
  var since=historyStart(viewer,room);
  if(since!=null && message.getCreatedAt().isBefore(since)) throw new ApiException(HttpStatus.FORBIDDEN,"MESSAGE_HISTORY_HIDDEN","Message is outside your visible history");
  return new Sent(roomDto(viewer,room),messageDto(viewer,message));
 }
 private String delivery(ChatMessage m) {
  if(m.getRecipientCount()==0) return "SENT";
  return m.getSeenCount()>=m.getRecipientCount()?"SEEN":m.getDeliveredCount()>=m.getRecipientCount()?"DELIVERED":"SENT";
 }
 private void captureRecipients(ChatMessage m) {
  int count=0;
  for(var member:members.findByRoomId(m.getRoomId())) {
   if(member.getUserId().equals(m.getSenderId()) || users.findById(member.getUserId()).filter(u -> u.getStatus()==com.qlink.features.user.enums.UserStatus.ACTIVE).isEmpty()) continue;
   var receipt=new MessageReceipt();receipt.setMessageId(m.getId());receipt.setUserId(member.getUserId());receipt.setMembershipId(member.getId());receipts.save(receipt);count++;
  }
  m.setRecipientCount(count);messages.flush();
 }
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
 public List<Message> acknowledge(UUID viewer,UUID roomId,Receipts request) {
  rooms.lockById(roomId).orElseThrow(() -> bad("Room unavailable"));
  var room=requireRoom(viewer,roomId);var member=membership(viewer,roomId);
  var result=new ArrayList<Message>();
  for(UUID id:new LinkedHashSet<>(request.messageIds())) {
   var m=visibleMessage(viewer,room,id);
   if(m.getSenderId().equals(viewer) || isHidden(viewer,id) || m.getRemovedAt()!=null) continue;
   var receipt=receipts.findByMessageIdAndUserId(id,viewer).orElse(null);
   // Visible old history does not make a newly joined member an original recipient.
   if(receipt==null || !receipt.getMembershipId().equals(member.getId())) continue;
   boolean changed=false;
   Instant now=Instant.now();
   if(receipt.getDeliveredAt()==null) {receipt.setDeliveredAt(now);m.setDeliveredCount(m.getDeliveredCount()+1);changed=true;}
   if(request.seen() && receipt.getSeenAt()==null) {receipt.setSeenAt(now);m.setSeenCount(m.getSeenCount()+1);changed=true;}
   if(changed) {
    receipts.flush();messages.flush();
    publishMessage(id,"MESSAGE_RECEIPT",null);
   }
   result.add(messageDto(viewer,m));
  }
  return result;
 }
 private ChatMessage visibleMessage(UUID viewer,ChatRoom room,UUID id) {
  var m=messages.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"MESSAGE_NOT_FOUND","Message unavailable"));
  var since=historyStart(viewer,room);
  if(!m.getRoomId().equals(room.getId()) || (since!=null && m.getCreatedAt().isBefore(since)))
   throw new ApiException(HttpStatus.NOT_FOUND,"MESSAGE_NOT_FOUND","Message unavailable");
  return m;
 }
 private void reply(UUID viewer,ChatRoom room,ChatMessage message,UUID id) {
  if(id==null) return;
  var original=visibleMessage(viewer,room,id);
  if(original.getRemovedAt()!=null || isHidden(viewer,id)) throw bad("Reply message unavailable");
  message.setReplyToId(id);
 }
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
 public Message edit(UUID viewer,UUID roomId,UUID id,Edit request) {
  users.lockById(viewer).orElseThrow(() -> bad("User unavailable"));
  rooms.lockById(roomId).orElseThrow(() -> bad("Room unavailable"));
  var room=requireRoom(viewer,roomId);var m=visibleMessage(viewer,room,id);
  if(room.getType()!=ChatRoomType.PERSONAL && membership(viewer,roomId).getRole()==ChatRoomMemberRole.MEMBER && !room.isMembersCanSend())
   throw new ApiException(HttpStatus.FORBIDDEN,"GROUP_PERMISSION_DENIED","Members cannot edit messages in this group");
  if(!m.getSenderId().equals(viewer)) throw new ApiException(HttpStatus.FORBIDDEN,"MESSAGE_ACCESS_DENIED","Only the sender can edit a message");
  if(m.getRemovedAt()!=null || isHidden(viewer,id) || m.getType()!=MessageType.TEXT) throw bad("Message cannot be edited");
  if(request.content()==null || request.content().isBlank() || request.content().length()>4000) throw bad("Message must contain 1–4000 characters");
  if(request.revision()==null || request.revision()!=m.getRevision()) throw new ApiException(HttpStatus.CONFLICT,"STALE_MESSAGE","Message changed; refresh before editing");
  m.setContent(request.content());m.setEditedAt(Instant.now());m.setRevision(m.getRevision()+1);messages.flush();
  publishMessage(id,"MESSAGE_UPDATED",null);
  return messageDto(viewer,m);
 }
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
 public Message delete(UUID viewer,UUID roomId,UUID id,boolean everyone) {
  users.lockById(viewer).orElseThrow(() -> bad("User unavailable"));
  rooms.lockById(roomId).orElseThrow(() -> bad("Room unavailable"));
  var room=requireRoom(viewer,roomId);var m=visibleMessage(viewer,room,id);
  if(everyone) {
   if(!canDelete(viewer,m)) throw new ApiException(HttpStatus.FORBIDDEN,"MESSAGE_ACCESS_DENIED","You cannot delete this message for everyone");
   if(m.getRemovedAt()==null) {
    m.setContent("");m.setRemovedAt(Instant.now());m.setRemovedBy(viewer);m.setRevision(m.getRevision()+1);messages.flush();
   }
  } else if(!isHidden(viewer,id)) {
   var h=new MessageHidden();h.setMessageId(id);h.setUserId(viewer);hidden.saveAndFlush(h);
  }
  publishMessage(id,everyone?"MESSAGE_UPDATED":"MESSAGE_HIDDEN",everyone?null:viewer);
  return messageDto(viewer,m);
 }
 private Room roomDto(UUID viewer,ChatRoom room) {
  PublicUserResponse profile=null;
  if(room.getType()==ChatRoomType.PERSONAL) {
   UUID other=partner(viewer,room.getId());
   profile=users.findById(other).map(userMapper::toPublic).orElse(new PublicUserResponse(other,null,"Unavailable user",null));
  }
  var latest=messages.visibleLatest(room.getId(),viewer,historyStart(viewer,room),PageRequest.of(0,1));
  Message last=latest.isEmpty()?null:messageDto(viewer,latest.get(0));
  return new Room(room.getId(),room.getCreatorId(),profile,room.getType()==ChatRoomType.PERSONAL?room.getLastMessageAt():(last==null?null:last.createdAt()),last,
   room.getType().name(),room.getGroupName(),room.getAvatarFileId()==null?null:"/api/v1/storage/files/"+room.getAvatarFileId()+"/download",
   members.findByRoomId(room.getId()).size(),room.getMessageHistoryVisibility().name(),room.getDescription(),
   membership(viewer,room.getId()).getRole().name(),room.isMembersCanSend(),room.isMembersCanInvite());
 }
 public Page<PublicUserResponse> contacts(UUID viewer,int page,int size) {
  current.requireActive(viewer);page(page,size);
  return users.contacts(viewer,null,PageRequest.of(page,size)).map(userMapper::toPublic);
 }
 @Transactional
 public Room createGroup(UUID viewer,CreateGroup request) {
  current.requireActive(viewer);
  String name=request.name()==null?"":request.name().trim();
  if(name.isEmpty() || name.length()>100) throw bad("Group name must contain 1-100 characters");
  var type=request.type()==null?ChatRoomType.GROUP_PRIVATE:request.type();
  if(type==ChatRoomType.PERSONAL) throw bad("A group type is required");
  var ids=request.memberIds();
  if(ids==null || ids.isEmpty() || ids.size()>199 || ids.stream().anyMatch(Objects::isNull) || ids.contains(viewer) || new HashSet<>(ids).size()!=ids.size())
   throw bad("Select distinct contacts other than yourself");
  for(UUID id:ids) if(users.contacts(viewer,id,PageRequest.of(0,1)).isEmpty()) throw bad("Every selected member must be an active contact");
  ChatRoom room=new ChatRoom();room.setCreatorId(viewer);room.setGroupName(name);room.setType(type);
  room.setMessageHistoryVisibility(request.messageHistoryVisibility()==null?MessageHistoryVisibility.VIEW_AFTER_JOIN:request.messageHistoryVisibility());
  rooms.saveAndFlush(room);
  Instant joined=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  var all=new ArrayList<>(ids);all.add(viewer);
  for(UUID id:all) {
   ChatRoomMember member=new ChatRoomMember();member.setRoomId(room.getId());member.setUserId(id);member.setJoinedAt(joined);
   member.setRole(id.equals(viewer)?ChatRoomMemberRole.OWNER:ChatRoomMemberRole.MEMBER);members.save(member);
  }
  members.flush();events.publishEvent(new com.qlink.features.chat.realtime.GroupChanged(room.getId()));return roomDto(viewer,room);
 }
 private ChatRoom requireGroup(UUID viewer,UUID id) {
  var room=requireRoom(viewer,id);
  if(room.getType()==ChatRoomType.PERSONAL) throw bad("A group room is required");return room;
 }
 public Room room(UUID viewer,UUID id) {return roomDto(viewer,requireRoom(viewer,id));}
 public Room group(UUID viewer,UUID id) { return roomDto(viewer,requireGroup(viewer,id)); }
 public List<GroupMember> groupMembers(UUID viewer,UUID id) {
  requireGroup(viewer,id);
  return members.findByRoomId(id).stream().map(m -> {
   var u=users.findById(m.getUserId()).map(userMapper::toPublic).orElse(new PublicUserResponse(m.getUserId(),null,"Unavailable user",null));
   return new GroupMember(u.id(),u.displayName(),u.username(),u.avatarUrl(),m.getRole().name(),m.getJoinedAt());
  }).toList();
 }
 public Lookup lookup(UUID viewer,UUID recipient) {
  discovery.recipient(viewer,recipient);
  return rooms.findByPersonalPairKey(pairKey(viewer,recipient))
    .map(r -> new Lookup(true,roomDto(viewer,r))).orElse(new Lookup(false,null));
 }
 public Page<Room> list(UUID viewer,int page,int size) {
  current.requireActive(viewer); page(page,size);
  return rooms.rooms(viewer,PageRequest.of(page,size)).map(r -> roomDto(viewer,r));
 }
 public History history(UUID viewer,UUID roomId,String cursor,int size) {
  var room=requireRoom(viewer,roomId); page(0,size);
  Instant before=null; UUID id=null;
  if(cursor!=null) {
   try {
    if(cursor.length()>256) throw new IllegalArgumentException();
    String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
    if(parts.length!=3 || !UUID.fromString(parts[0]).equals(roomId)) throw new IllegalArgumentException();
    before=Instant.parse(parts[1]); id=UUID.fromString(parts[2]);
   } catch(RuntimeException e) { throw bad("Invalid message cursor"); }
  }
  var found=messages.history(roomId,historyStart(viewer,room),before,id,PageRequest.of(0,size+1));
  boolean more=found.size()>size;
  var visible=found.subList(0,Math.min(size,found.size()));
  String next=null;
  if(more) {
   ChatMessage last=visible.get(visible.size()-1);
   next=Base64.getUrlEncoder().withoutPadding().encodeToString((roomId+"|"+last.getCreatedAt()+"|"+last.getId()).getBytes(StandardCharsets.UTF_8));
  }
  return new History(visible.stream().map(m -> messageDto(viewer,m)).toList(),next);
 }
 @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
 public Sent sendPersonal(UUID sender,PersonalSend request) {
  return send(sender,request.recipientId(),null,request.clientMessageId(),request.content(),request.type(),request.fileId(),request.durationMs(),request.replyToId());
 }
 @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
 public Sent sendExisting(UUID sender,UUID roomId,Send request) {
  return send(sender,null,roomId,request.clientMessageId(),request.content(),request.type(),request.fileId(),request.durationMs(),request.replyToId());
 }
 private Sent send(UUID sender,UUID recipient,UUID requestedRoom,UUID key,String content,String type,UUID fileId,Long durationMs,UUID replyToId) {
  boolean voice="VOICE".equals(type);
  boolean attachment="IMAGE".equals(type) || "FILE".equals(type);
  MessageType messageType=type==null?MessageType.TEXT:parseType(type);
  if(attachment && (key==null || fileId==null || durationMs!=null || (content!=null && !content.isEmpty()))) throw bad("Invalid attachment message");
  if(voice && (key==null || fileId==null || durationMs==null || durationMs<300 || durationMs>120000 || (content!=null && !content.isEmpty()))) throw bad("Invalid voice message");
  if(!voice && !attachment && (fileId!=null || durationMs!=null)) throw bad("Text cannot contain audio");
  if(!voice && !attachment && (key==null || content==null || content.isBlank() || content.length()>4000)) throw bad("Message must contain 1–4000 characters");
  // Each retry starts a new transaction; never continue in a transaction marked rollback-only.
  for(int attempt=0;attempt<3;attempt++) {
   try { return writes.execute(status -> persist(sender,recipient,requestedRoom,key,voice || attachment?"":content,fileId,durationMs,messageType,replyToId)); }
   catch(DataIntegrityViolationException | TransientDataAccessException ex) {
    if(attempt==2) throw new ApiException(HttpStatus.CONFLICT,"SEND_CONFLICT","Concurrent change; retry with the same message identifier");
   }
  }
  throw new IllegalStateException();
 }
 private MessageType parseType(String type) {
  try { return MessageType.valueOf(type); } catch(IllegalArgumentException ex) { throw bad("Unsupported message type"); }
 }
 private Sent persist(UUID sender,UUID recipient,UUID requestedRoom,UUID key,String content,UUID fileId,Long durationMs,MessageType messageType,UUID replyToId) {
  if(requestedRoom!=null && rooms.findById(requestedRoom).map(r -> r.getType()!=ChatRoomType.PERSONAL).orElse(false)) {
   users.lockById(sender).orElseThrow();
   var room=rooms.lockById(requestedRoom).orElseThrow();
   entityManager.refresh(room); // Initial room lookup may predate a concurrent permission change.
   requireRoom(sender,requestedRoom);
   var senderMembership=membership(sender,requestedRoom);
   if(senderMembership.getRole()==ChatRoomMemberRole.MEMBER && !room.isMembersCanSend())
    throw new ApiException(HttpStatus.FORBIDDEN,"GROUP_PERMISSION_DENIED","Members cannot send messages in this group");
   var prior=messages.previous(sender,key);
   if(prior.isPresent()) {
    var message=prior.get();
    if(room.getMessageHistoryVisibility()==MessageHistoryVisibility.VIEW_AFTER_JOIN &&
       message.getCreatedAt().isBefore(senderMembership.getJoinedAt()))
     throw new ApiException(HttpStatus.FORBIDDEN,"MESSAGE_HISTORY_HIDDEN","Message is outside your visible history");
    if(message.getDeletedAt()!=null || message.getRemovedAt()!=null || !Objects.equals(message.getReplyToId(),replyToId) || !message.getRoomId().equals(requestedRoom) || !message.getContent().equals(content) || !Objects.equals(message.getFileId(),fileId) || (message.getType()!=messageType || (messageType==MessageType.VOICE && Math.abs(message.getDurationMs()-durationMs)>1000))) throw conflict();
    return new Sent(roomDto(sender,room),messageDto(sender,message));
   }
   var message=new ChatMessage();message.setRoomId(requestedRoom);message.setSenderId(sender);message.setClientMessageId(key);message.setContent(content);
   if(messageType==MessageType.VOICE) voiceFiles.attach(sender,message,fileId,durationMs);
   else if(fileId!=null) voiceFiles.attachFile(sender,message,fileId,messageType);
   reply(sender,room,message,replyToId);
   messages.saveAndFlush(message);captureRecipients(message);room.setLastMessageAt(message.getCreatedAt());rooms.saveAndFlush(room);
   publishMessage(message.getId(),"MESSAGE_CREATED",null);
   return new Sent(roomDto(sender,room),messageDto(sender,message));
  }
  UUID other=recipient;
  if(requestedRoom!=null) { requireRoom(sender,requestedRoom); other=partner(sender,requestedRoom); }
  if(other==null || sender.equals(other)) throw bad("A different recipient is required");
  // Global UUID order prevents opposite-direction and cross-pair deadlocks.
  // Locking the sender also serializes its idempotency scope across all rooms.
  var lockIds=new ArrayList<>(List.of(sender,other)); lockIds.sort(Comparator.comparing(UUID::toString));
  for(UUID userId:lockIds) users.lockById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"USER_NOT_FOUND","User unavailable"));
  discovery.recipient(sender,other);
  ChatRoom room=requestedRoom==null ? rooms.findByPersonalPairKey(pairKey(sender,other)).orElse(null) : requireRoom(sender,requestedRoom);
  var prior=messages.previous(sender,key);
  if(prior.isPresent()) {
   ChatMessage message=prior.get();
   if(message.getDeletedAt()!=null || message.getRemovedAt()!=null || !Objects.equals(message.getReplyToId(),replyToId) || room==null || !message.getRoomId().equals(room.getId()) || !message.getContent().equals(content) || !Objects.equals(message.getFileId(),fileId) || (message.getType()!=messageType || (messageType==MessageType.VOICE && Math.abs(message.getDurationMs()-durationMs)>1000))) throw conflict();
   return new Sent(roomDto(sender,room),messageDto(sender,message));
  }
  if(room==null) {
   room=new ChatRoom(); room.setCreatorId(sender); room.setPersonalPairKey(pairKey(sender,other));
   rooms.saveAndFlush(room);
   for(UUID id:List.of(sender,other)) {
    ChatRoomMember member=new ChatRoomMember(); member.setRoomId(room.getId()); member.setUserId(id); member.setJoinedAt(Instant.now()); members.save(member);
   }
   members.flush();
  }
  rooms.lockById(room.getId()).orElseThrow();
  partner(sender,room.getId());
  ChatMessage message=new ChatMessage(); message.setRoomId(room.getId()); message.setSenderId(sender);
  message.setClientMessageId(key); message.setContent(content); if(messageType==MessageType.VOICE) voiceFiles.attach(sender,message,fileId,durationMs);
   else if(fileId!=null) voiceFiles.attachFile(sender,message,fileId,messageType);
   reply(sender,room,message,replyToId);
   messages.saveAndFlush(message);captureRecipients(message);
  room.setLastMessageAt(message.getCreatedAt()); rooms.saveAndFlush(room);
  publishMessage(message.getId(),"MESSAGE_CREATED",null);
  return new Sent(roomDto(sender,room),messageDto(sender,message));
 }
}
