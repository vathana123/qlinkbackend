package com.qlink.features.chat.realtime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import org.slf4j.*;
@Component
public class ChatEventListener {
 private static final Logger log=LoggerFactory.getLogger(ChatEventListener.class);
 @org.springframework.beans.factory.annotation.Autowired private ChatEventRecovery recovery;
 private final ChatEventDelivery delivery;
 private final com.qlink.features.storage.service.StorageCleanupService cleanup;
 public ChatEventListener(ChatEventDelivery delivery,com.qlink.features.storage.service.StorageCleanupService cleanup) {this.delivery=delivery;this.cleanup=cleanup;}
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
