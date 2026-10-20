package com.qlink.features.chat.realtime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import org.slf4j.*;
@Component
public class ChatEventListener {
 private static final Logger log=LoggerFactory.getLogger(ChatEventListener.class);
 private final ChatEventDelivery delivery;
 public ChatEventListener(ChatEventDelivery delivery) {this.delivery=delivery;}
 @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
 public void committed(MessageCommitted event) {
  try {delivery.deliver(event);}
  catch(RuntimeException ex) {log.warn("Chat event unavailable for message {} ({})",event.messageId(),ex.getClass().getSimpleName());}
 }
}
