package com.qlink.features.chat.realtime;
import com.qlink.features.chat.repository.ChatEventJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
import java.util.UUID;
@Service
public class ChatEventRecovery {
 private final ChatEventJobRepository jobs;
 private final ChatEventDelivery delivery;
 private final TransactionTemplate tx;
 public ChatEventRecovery(ChatEventJobRepository jobs,ChatEventDelivery delivery,PlatformTransactionManager manager) {
  this.jobs=jobs;this.delivery=delivery;this.tx=new TransactionTemplate(manager);this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
 }
 public void deliver(UUID id) {
  tx.executeWithoutResult(status -> {
   var job=jobs.lock(id).orElse(null);
   if(job==null || job.getFinishedAt()!=null || job.getNextAttemptAt().isAfter(Instant.now())) return;
   try {
    delivery.deliver(new MessageCommitted(job.getMessageId(),job.getEventType(),job.getOnlyUser(),job.getId()));
    job.setFinishedAt(Instant.now());
   } catch(RuntimeException ex) {
    job.setAttempts(job.getAttempts()+1);
    job.setNextAttemptAt(Instant.now().plusSeconds(1L<<Math.min(job.getAttempts(),8)));
   }
  });
 }
 @Scheduled(fixedDelay=5000,initialDelay=5000)
 public void retry() {
  for(var id:jobs.due(Instant.now(),PageRequest.of(0,50))) {
   try {deliver(id);} catch(RuntimeException ignored) { /* A later bounded batch retries. */ }
  }
 }
 @Scheduled(fixedDelay=86400000,initialDelay=86400000)
 public void purge() {tx.executeWithoutResult(s -> jobs.purge(Instant.now().minusSeconds(604800)));}
}
