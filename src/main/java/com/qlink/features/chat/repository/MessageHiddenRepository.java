package com.qlink.features.chat.repository;
import java.util.UUID;
import com.qlink.features.chat.entity.MessageHidden;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MessageHiddenRepository extends JpaRepository<MessageHidden,UUID> {
 boolean existsByMessageIdAndUserId(UUID messageId,UUID userId);
}
