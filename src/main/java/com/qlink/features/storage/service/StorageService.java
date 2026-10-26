package com.qlink.features.storage.service;

import com.qlink.features.storage.config.StorageProperties;
import com.qlink.features.storage.dto.StoredFileResponse;
import com.qlink.features.storage.entity.StoredFile;
import com.qlink.features.storage.enums.*;
import com.qlink.features.storage.mapper.StoredFileMapper;
import com.qlink.features.storage.repository.StoredFileRepository;
import com.qlink.common.exception.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.*;
import java.io.*;
import java.nio.file.NoSuchFileException;
import java.util.UUID;

@Service
public class StorageService {
    public record Download(StoredFileResponse metadata, InputStream stream) implements AutoCloseable {
        @Override public void close() throws IOException { stream.close(); }
    }
    private static final Logger log=LoggerFactory.getLogger(StorageService.class);
    private final com.qlink.features.user.repository.UserRepository users;
    private final FileStorageProvider provider;
    private final VoiceFiles voiceFiles;
    private final StoredFileRepository files;
    private final StoredFileMapper mapper;
    private final FileContentPolicy content;
    private final StorageAccessPolicy access;
    private final StorageCleanupService cleanup;
    private final StorageProperties properties;
    private final TransactionTemplate transaction;
    public StorageService(FileStorageProvider provider, StoredFileRepository files, StoredFileMapper mapper,
                          FileContentPolicy content, StorageAccessPolicy access, StorageCleanupService cleanup,
                          StorageProperties properties, VoiceFiles voiceFiles, PlatformTransactionManager manager,
                          com.qlink.features.user.repository.UserRepository users) {
        this.users=users;
        this.voiceFiles=voiceFiles; this.provider=provider; this.files=files; this.mapper=mapper; this.content=content;
        this.access=access; this.cleanup=cleanup; this.properties=properties;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public StoredFileResponse upload(Jwt jwt, MultipartFile input, FileCategory category) {
        return upload(jwt,input,category,false);
    }
    public StoredFileResponse upload(Jwt jwt, MultipartFile input, FileCategory category, boolean voice) {
        return upload(jwt,input,category,voice,null);
    }
    public StoredFileResponse uploaded(Jwt jwt,UUID key) {
        return transaction.execute(tx -> {
            var file=files.byUploadKey(access.actor(jwt),key).orElseThrow(StorageErrors::missing);
            if(file.getDeletedAt()!=null) throw StorageErrors.missing();
            return mapper.toResponse(file);
        });
    }
    public StoredFileResponse upload(Jwt jwt,MultipartFile input,FileCategory category,boolean voice,UUID uploadKey) {
        UUID actor=access.actor(jwt);
        if(voice) {
            if(input.getSize()>VoiceFiles.MAX_BYTES) throw StorageErrors.tooLarge();
            try(var audio=input.getInputStream()) { VoiceFiles.wavDuration(audio); }
            catch(IOException ex) { throw StorageErrors.unavailable(); }
        }
        if (input.isEmpty()) throw StorageErrors.invalid("File must not be empty");
        if (input.getSize()>properties.maxFileSize().toBytes()) throw StorageErrors.tooLarge();
        String name=content.filename(input.getOriginalFilename());
        String key=UUID.randomUUID()+".blob";
        boolean stored=false;
        try {
            FileStorageProvider.StoredBytes bytes;
            try (var stream=input.getInputStream()) {
                bytes=provider.store(stream,key,properties.maxFileSize().toBytes(),in -> content.detect(in,name,category));
                stored=true;
            }
            var result=transaction.execute(tx -> {
                // Recheck identity before publication after a potentially slow upload.
                access.recheck(jwt);
                if(uploadKey!=null) {
                    users.lockById(actor).orElseThrow(StorageErrors::missing);
                    var previous=files.byUploadKey(actor,uploadKey);
                    if(previous.isPresent()) {
                        var old=previous.get();
                        if(old.getDeletedAt()!=null || !old.getChecksumSha256().equals(bytes.sha256()) || old.isVoiceRecording()!=voice)
                            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,"UPLOAD_CONFLICT","Upload identifier already used");
                        return mapper.toResponse(old);
                    }
                }
                var file=new StoredFile();
                file.setUploadKey(uploadKey); file.setVoiceRecording(voice); file.setOriginalName(name); file.setStorageKey(key); file.setContentType(bytes.contentType());
                file.setSizeBytes(bytes.size()); file.setChecksumSha256(bytes.sha256());
                file.setCategory(content.category(bytes.contentType())); file.setUploadedBy(actor);
                file.setStatus(StoredFileStatus.AVAILABLE);
                return mapper.toResponse(files.saveAndFlush(file));
            });
            if(uploadKey!=null) {
                var retained=transaction.execute(tx -> files.findById(result.id()).orElseThrow().getStorageKey());
                if(!key.equals(retained)) provider.delete(key);
            }
            return result;
        } catch (IOException | RuntimeException ex) {
            if (stored) {
                try { provider.delete(key); }
                catch (IOException | RuntimeException cleanupError) {
                    log.warn("Unreferenced storage object requires reconciliation: {} ({})",key,cleanupError.getClass().getSimpleName());
                }
            }
            if (ex instanceof ApiException api) throw api;
            log.warn("Storage upload failed ({})",ex.getClass().getSimpleName());
            throw StorageErrors.unavailable();
        }
    }
    private StoredFile available(UUID actor, UUID id) {
        var file=files.findById(id).orElseThrow(StorageErrors::missing);
        voiceFiles.requireRead(actor,file);
        if (file.getStatus()!=StoredFileStatus.AVAILABLE) throw StorageErrors.missing();
        return file;
    }
    public StoredFileResponse metadata(Jwt jwt, UUID id) {
        return transaction.execute(tx -> mapper.toResponse(available(access.actor(jwt),id)));
    }
    public Download download(Jwt jwt, UUID id) {
        var file=transaction.execute(tx -> available(access.actor(jwt),id));
        try { return new Download(mapper.toResponse(file),provider.load(file.getStorageKey())); }
        catch (NoSuchFileException ex) { throw StorageErrors.missing(); }
        catch (IOException ex) { throw StorageErrors.unavailable(); }
    }
    public void delete(Jwt jwt, UUID id) {
        transaction.executeWithoutResult(tx -> {
            UUID actor=access.actor(jwt);
            var file=files.lockIncludingDeleted(id).orElseThrow(StorageErrors::missing);
            access.requireOwner(actor,file);
            voiceFiles.requireUnattached(id);
            if (file.getDeletedAt()==null) file.markDeleted(actor);
            files.flush();
        });
        // Deliberately outside the transaction: access stays revoked if disk cleanup fails.
        cleanup.cleanupDeleted(id);
    }
}
