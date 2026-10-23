package com.qlink.features.user.service;

import com.qlink.common.exception.ApiException;
import com.qlink.features.user.dto.*;
import com.qlink.features.user.repository.UserRepository;
import com.qlink.features.user.mapper.UserMapper;
import com.qlink.features.storage.service.*;
import com.qlink.features.storage.repository.StoredFileRepository;
import com.qlink.features.storage.enums.FileCategory;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

@Service
public class ProfileService {
    private final UserRepository users;
    private final UserMapper mapper;
    private final StorageService storage;
    private final StorageAccessPolicy access;
    private final StoredFileRepository files;
    private final VoiceFiles attachments;
    private final StorageCleanupService cleanup;
    private final TransactionTemplate transaction;
    public ProfileService(UserRepository users, UserMapper mapper, StorageService storage, StorageAccessPolicy access,
            StoredFileRepository files, VoiceFiles attachments, StorageCleanupService cleanup, PlatformTransactionManager manager) {
        this.users=users; this.mapper=mapper; this.storage=storage; this.access=access;
        this.files=files; this.attachments=attachments; this.cleanup=cleanup;
        transaction=new TransactionTemplate(manager);
    }
    public UserResponse update(Jwt jwt, ProfileRequest request) {
        UUID actor=access.actor(jwt);
        try {
            return transaction.execute(tx -> {
                var user=users.lockById(actor).orElseThrow(() -> ApiException.unauthorized("USER_UNAVAILABLE","User unavailable"));
                if(request.displayName()!=null) user.setDisplayName(request.displayName().strip());
                if(request.username()!=null) user.setUsername(DiscoveryService.normalize(request.username()));
                user.recordActor(actor);
                users.flush();
                return mapper.toResponse(user);
            });
        } catch(org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT,"USERNAME_TAKEN","This username is already taken");
        }
    }
    public UserResponse avatar(Jwt jwt, MultipartFile input) {
        UUID actor=access.actor(jwt);
        var uploaded=input==null ? null : storage.upload(jwt,input,FileCategory.IMAGE);
        UUID[] old={null};
        UserResponse result;
        try {
            result=transaction.execute(tx -> {
                access.actor(jwt);
                var user=users.lockById(actor).orElseThrow(() -> ApiException.unauthorized("USER_UNAVAILABLE","User unavailable"));
                old[0]=user.getAvatarFileId();
                user.setAvatarFileId(uploaded==null ? null : uploaded.id());
                user.setAvatarUrl(uploaded==null ? null : "/api/v1/storage/files/"+uploaded.id()+"/download");
                user.recordActor(actor);
                users.flush();
                if(old[0]!=null && users.avatarReferences(old[0])==0) {
                    var previous=files.lockIncludingDeleted(old[0]).orElseThrow();
                    attachments.requireUnattached(old[0]);
                    previous.markDeleted(actor);
                    files.flush();
                }
                return mapper.toResponse(user);
            });
        } catch(RuntimeException ex) {
            if(uploaded!=null) {
                try {
                    // The session may have expired during upload. Compensate using the server-owned file ID.
                    transaction.executeWithoutResult(tx -> {
                        var abandoned=files.lockIncludingDeleted(uploaded.id()).orElseThrow();
                        access.requireOwner(actor,abandoned);
                        attachments.requireUnattached(uploaded.id());
                        abandoned.markDeleted(actor);
                        files.flush();
                    });
                    cleanup.cleanupDeleted(uploaded.id());
                } catch(RuntimeException cleanupFailure) { ex.addSuppressed(cleanupFailure); }
            }
            throw ex;
        }
        if(old[0]!=null) cleanup.cleanupDeleted(old[0]);
        return result;
    }
}
