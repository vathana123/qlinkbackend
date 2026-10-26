package com.qlink.features.chat.realtime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import org.slf4j.*;
@Component
@RequiredArgsConstructor
public class ChatEventListener {
 private static final Logger log=LoggerFactory.getLogger(ChatEventListener.class);
 private final ChatEventDelivery delivery;
 private final com.qlink.features.storage.service.StorageCleanupService cleanup;
 private final ChatEventRecovery recovery;
 @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
 public void groupChanged(GroupChanged event) {
  try {delivery.deliver(event);}
  catch(RuntimeException ex) {log.warn("Group event unavailable ({})",ex.getClass().getSimpleName());}
  if(event.retiredAvatar()!=null) cleanup.cleanupDeleted(event.retiredAvatar());
 }
 @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
 public void committed(MessageCommitted event) {
  try {if(event.jobId()!=null) recovery.deliver(event.jobId());else delivery.deliver(event);}
  catch(RuntimeException ex) {log.warn("Chat event unavailable for message {} ({})",event.messageId(),ex.getClass().getSimpleName());}
 }
}
