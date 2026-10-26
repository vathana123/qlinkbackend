package com.qlink.features.push;
import com.qlink.common.exception.ApiException;
import com.qlink.features.chat.entity.ChatMessage;
import com.qlink.features.chat.repository.*;
import com.qlink.features.chat.enums.*;
import com.qlink.features.user.service.UserService;
import com.qlink.features.auth.repository.AuthSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;
@Service
public class PushService {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PushService.class);
 private final PushDeviceRepository devices;private final RoomNotificationPreferenceRepository preferences;
 private final PushDeliveryRepository deliveries;private final PushGateway gateway;
 private final ChatMemberRepository members;private final ChatRoomRepository rooms;private final ChatMessageRepository messages;
 private final AuthSessionRepository sessions;private final UserService users;private final MessageReceiptRepository receipts;
 private final TransactionTemplate tx;
 public PushService(PushDeviceRepository devices,RoomNotificationPreferenceRepository preferences,PushDeliveryRepository deliveries,PushGateway gateway,
  ChatMemberRepository members,ChatRoomRepository rooms,ChatMessageRepository messages,AuthSessionRepository sessions,UserService users,MessageReceiptRepository receipts,PlatformTransactionManager manager) {
  this.devices=devices;this.preferences=preferences;this.deliveries=deliveries;this.gateway=gateway;this.members=members;this.rooms=rooms;this.messages=messages;this.sessions=sessions;this.users=users;this.receipts=receipts;this.tx=new TransactionTemplate(manager);
 }
 private ApiException denied(){return new ApiException(HttpStatus.FORBIDDEN,"PUSH_ACCESS_DENIED","Active conversation membership required");}
 private void membership(UUID actor,UUID room){users.requireActive(actor);rooms.findById(room).orElseThrow(this::denied);if(members.findByRoomId(room).stream().noneMatch(m->m.getUserId().equals(actor)))throw denied();}
 @Transactional public void register(UUID actor,UUID session,UUID installation,String token,String platform) {
  users.requireActive(actor);
  var auth=sessions.findLockedById(session).orElseThrow(this::denied);
  if(!auth.getUserId().equals(actor)||auth.getRevokedAt()!=null||!auth.getExpiresAt().isAfter(Instant.now()))throw denied();
  if(!Set.of("ANDROID","IOS").contains(platform))throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PLATFORM","Android or iOS required");
  String hash=com.qlink.security.jwt.TokenHash.sha256(token);
  var device=devices.findByInstallationId(installation).orElseGet(PushDevice::new);
  var prior=devices.findByTokenHash(hash);
  if(prior.isPresent() && !Objects.equals(prior.get().getId(),device.getId())) {devices.delete(prior.get());devices.flush();}
  device.setInstallationId(installation);device.setUserId(actor);device.setSessionId(session);device.setToken(token);device.setTokenHash(hash);device.setPlatform(platform);devices.saveAndFlush(device);
  log.info("Push device registered user={} installation={} platform={} dispatchEnabled={}",actor,installation,platform,gateway.enabled());
 }
 @Transactional public void unregister(UUID actor,UUID session,UUID installation) {
  devices.findByInstallationId(installation).filter(d -> d.getUserId().equals(actor)&&d.getSessionId().equals(session)).ifPresent(devices::delete);
 }
 @Transactional(readOnly=true) public boolean muted(UUID actor,UUID room){membership(actor,room);return mutedInternal(actor,room);}
 private boolean mutedInternal(UUID actor,UUID room){return preferences.findByRoomIdAndUserId(room,actor).map(RoomNotificationPreference::isMuted).orElse(false);}
 @Transactional public void mute(UUID actor,UUID room,boolean muted) {
  membership(actor,room);var p=preferences.findByRoomIdAndUserId(room,actor).orElseGet(RoomNotificationPreference::new);
  p.setRoomId(room);p.setUserId(actor);p.setMuted(muted);preferences.save(p);
 }
 // Called inside the message transaction. Unique(message,device) prevents duplicate enqueue.
 public void enqueue(ChatMessage message) {
  if(!gateway.enabled()){log.debug("Push skipped message={} reason=disabled",message.getId());return;}
  int queued=0;
  for(var member:members.findByRoomId(message.getRoomId())) {
   UUID user=member.getUserId();if(user.equals(message.getSenderId()))continue;
   if(mutedInternal(user,message.getRoomId())){log.debug("Push skipped message={} recipient={} reason=muted",message.getId(),user);continue;}
   for(var device:devices.findByUserId(user)) {
    var job=new PushDelivery();job.setMessageId(message.getId());job.setDeviceId(device.getId());job.setRecipientId(user);job.setNextAttemptAt(Instant.now());deliveries.save(job);
    queued++;
   }
  }
  log.debug("Push enqueue message={} devices={}",message.getId(),queued);
 }
 public void dispatch(UUID id) {
  tx.executeWithoutResult(status -> {
   var job=deliveries.lock(id).orElse(null);
   if(job==null||job.getFinishedAt()!=null||job.getNextAttemptAt().isAfter(Instant.now()))return;
   var device=devices.findById(job.getDeviceId()).orElse(null);var message=messages.findById(job.getMessageId()).orElse(null);
   if(device==null||message==null||!device.getUserId().equals(job.getRecipientId())||message.getRemovedAt()!=null||message.getSenderId().equals(device.getUserId())){log.debug("Push skipped notification={} reason=missing_removed_or_reassigned",id);job.setFinishedAt(Instant.now());return;}
   var session=sessions.findById(device.getSessionId()).orElse(null);
   var room=rooms.findById(message.getRoomId()).orElse(null);
   var member=members.findByRoomId(message.getRoomId()).stream().filter(m->m.getUserId().equals(device.getUserId())).findFirst().orElse(null);
   var receipt=receipts.findByMessageIdAndUserId(message.getId(),device.getUserId()).orElse(null);
   String skipped=null;
   if(session==null||session.getRevokedAt()!=null||!session.getExpiresAt().isAfter(Instant.now())) skipped="inactive_session";
   else if(room==null||member==null||receipt==null||!receipt.getMembershipId().equals(member.getId())) skipped="membership_or_receipt";
   else if(receipt.getSeenAt()!=null) skipped="seen";
   else if(mutedInternal(device.getUserId(),room.getId())) skipped="muted";
   else if(messages.hiddenCount(message.getId(),device.getUserId())>0) skipped="hidden";
   else if(room.getType()!=ChatRoomType.PERSONAL&&room.getMessageHistoryVisibility()==MessageHistoryVisibility.VIEW_AFTER_JOIN&&message.getCreatedAt().isBefore(member.getJoinedAt())) skipped="history_boundary";
   if(skipped!=null){log.debug("Push skipped notification={} reason={}",id,skipped);job.setFinishedAt(Instant.now());return;}
   try {users.requireActive(device.getUserId());gateway.send(device,room.getId(),message.getId(),device.getUserId(),job.getId());job.setFinishedAt(Instant.now());log.debug("FCM accepted notification={} recipient={}",id,device.getUserId());}
   catch(PushGateway.InvalidToken ex){devices.delete(device);job.setFinishedAt(Instant.now());log.info("Push device removed notification={} reason=unregistered",id);}
   catch(Exception ex){job.setAttempts(job.getAttempts()+1);job.setNextAttemptAt(Instant.now().plusSeconds(1L<<Math.min(job.getAttempts(),8)));log.warn("Push retry notification={} attempt={} failureType={}",id,job.getAttempts(),ex.getClass().getSimpleName());}
  });
 }
 @Scheduled(fixedDelayString="${qlink.push.poll-ms:3000}",initialDelayString="${qlink.push.initial-delay-ms:3000}") public void sendPending(){if(!gateway.enabled())return;for(var id:deliveries.due(Instant.now(),PageRequest.of(0,25))){try{dispatch(id);}catch(RuntimeException ex){log.warn("Push job failed notification={} failureType={}",id,ex.getClass().getSimpleName());}}}
 @Scheduled(fixedDelay=86400000,initialDelay=86400000) public void purge(){tx.executeWithoutResult(s->deliveries.purge(Instant.now().minusSeconds(604800)));}
}
