package com.qlink.storage;
import com.qlink.features.storage.config.StorageProperties;
import com.qlink.features.storage.provider.LocalFileStorageProvider;
import com.qlink.features.storage.service.*;
import com.qlink.features.storage.repository.StoredFileRepository;
import com.qlink.features.storage.mapper.StoredFileMapper;
import com.qlink.features.storage.dto.StoredFileResponse;
import com.qlink.features.storage.enums.*;
import com.qlink.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageCommitFailureTest {
    @TempDir Path directory;
    @Test void commitFailureAfterFlushRemovesPublishedBytes() throws Exception {
        var properties=new StorageProperties(directory.toString(),DataSize.ofMegabytes(25));
        var provider=new LocalFileStorageProvider(properties);
        var files=mock(StoredFileRepository.class); var mapper=mock(StoredFileMapper.class);
        var access=mock(StorageAccessPolicy.class); UUID actor=UUID.randomUUID();
        when(access.actor(any())).thenReturn(actor);
        when(mapper.toResponse(any())).thenReturn(new StoredFileResponse(UUID.randomUUID(),"notes.txt","text/plain",5,FileCategory.DOCUMENT,StoredFileStatus.AVAILABLE,Instant.now()));
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doThrow(new TransactionSystemException("commit failure")).when(manager).commit(any());
        var service=new StorageService(provider,files,mapper,new FileContentPolicy(),access,mock(StorageCleanupService.class),properties,mock(com.qlink.features.storage.service.VoiceFiles.class),manager);
        var token=Jwt.withTokenValue("test").header("alg","test").subject(actor.toString()).build();
        assertThatThrownBy(() -> service.upload(token,new MockMultipartFile("file","notes.txt","text/plain","hello".getBytes()),null))
            .isInstanceOf(ApiException.class).extracting("code").isEqualTo("STORAGE_UNAVAILABLE");
        verify(files).saveAndFlush(any());
        try(var paths=Files.list(directory)) { assertThat(paths).isEmpty(); }
    }
}
