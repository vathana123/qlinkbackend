package com.qlink.features.storage.service;
import com.qlink.features.storage.repository.StoredFileRepository;
import com.qlink.features.chat.repository.ChatMessageRepository;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;

/** Unsent voice uploads expire after 24 hours; deleted messages revoke access immediately. */
@Component @EnableScheduling
public class VoiceCleanup {
    private final StoredFileRepository files;
    private final ChatMessageRepository messages;
    private final StorageCleanupService cleanup;
    private final TransactionTemplate tx;
    public VoiceCleanup(StoredFileRepository files, ChatMessageRepository messages, StorageCleanupService cleanup, PlatformTransactionManager manager) {
        this.files=files; this.messages=messages; this.cleanup=cleanup; this.tx=new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelay=3600000, initialDelay=3600000)
    public void run() {
        Instant before=Instant.now().minusSeconds(86400);
        for(var candidate:files.voiceCleanupCandidates(before)) {
            var id=java.util.UUID.fromString(candidate);
            tx.executeWithoutResult(status -> {
                var file=files.lockIncludingDeleted(id).orElseThrow();
                var attached=messages.includingDeletedByFileId(id);
                if(file.getDeletedAt()==null && (attached.isPresent() ? messages.liveFileReferences(id)==0 : file.getCreatedAt().isBefore(before)))
                    file.markDeleted(file.getUploadedBy());
            });
            if(cleanup.cleanupDeleted(id)) tx.executeWithoutResult(status -> {
                var file=files.lockIncludingDeleted(id).orElseThrow();
                // Finished tombstones must not starve later batches of pending cleanup.
                if(file.getDeletedAt()!=null) file.setVoiceRecording(false);
            });
        }
    }
}
