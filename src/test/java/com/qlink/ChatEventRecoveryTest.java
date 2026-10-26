package com.qlink;
import com.qlink.features.chat.entity.ChatEventJob;
import com.qlink.features.chat.repository.ChatEventJobRepository;
import com.qlink.features.chat.realtime.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ChatEventRecoveryTest {
 @Test void failedCommittedEventsRemainDurableAndRetryWithStableIdentity() {
  var jobs=mock(ChatEventJobRepository.class);var delivery=mock(ChatEventDelivery.class);
  var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
  var job=new ChatEventJob();UUID id=UUID.randomUUID();org.springframework.test.util.ReflectionTestUtils.setField(job,"id",id);
  job.setMessageId(UUID.randomUUID());job.setEventType("MESSAGE_UPDATED");job.setNextAttemptAt(Instant.now().minusSeconds(1));
  when(jobs.lock(id)).thenReturn(Optional.of(job));
  doThrow(new IllegalStateException("Broker down")).when(delivery).deliver(any(MessageCommitted.class));
  var recovery=new ChatEventRecovery(jobs,delivery,manager);recovery.deliver(id);
  assertThat(job.getFinishedAt()).isNull();assertThat(job.getAttempts()).isEqualTo(1);
  assertThat(job.getNextAttemptAt()).isAfter(Instant.now());
  doNothing().when(delivery).deliver(any(MessageCommitted.class));job.setNextAttemptAt(Instant.now().minusSeconds(1));recovery.deliver(id);
  assertThat(job.getFinishedAt()).isNotNull();
  verify(delivery,times(2)).deliver(new MessageCommitted(job.getMessageId(),"MESSAGE_UPDATED",null,id));
  recovery.deliver(id);verifyNoMoreInteractions(delivery);
 }
}
