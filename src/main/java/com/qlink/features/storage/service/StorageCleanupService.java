package com.qlink.features.storage.service;

import com.qlink.features.storage.repository.StoredFileRepository;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.util.UUID;

@Service
public class StorageCleanupService {
    private static final Logger log=LoggerFactory.getLogger(StorageCleanupService.class);
    private final StoredFileRepository files;
    private final FileStorageProvider provider;
    private final TransactionTemplate transaction;
    public StorageCleanupService(StoredFileRepository files, FileStorageProvider provider, PlatformTransactionManager manager) {
        this.files=files; this.provider=provider;
        transaction=new TransactionTemplate(manager); transaction.setReadOnly(true);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    /** Internal retry entry point; only committed tombstones can have their bytes removed. */
    public boolean cleanupDeleted(UUID id) {
        try {
            String key=transaction.execute(tx -> files.findTombstone(id).map(f -> f.getStorageKey()).orElse(null));
            if (key == null) return false;
            provider.delete(key);
            return true;
        } catch (IOException | RuntimeException ex) {
            log.warn("Storage cleanup pending for file {} ({})",id,ex.getClass().getSimpleName());
            return false;
        }
    }
}
