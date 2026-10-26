package com.qlink.features.chat.repository;
import com.qlink.features.chat.entity.MessageReceipt;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MessageReceiptRepository extends JpaRepository<MessageReceipt,UUID> {
 Optional<MessageReceipt> findByMessageIdAndUserId(UUID messageId,UUID userId);
}
