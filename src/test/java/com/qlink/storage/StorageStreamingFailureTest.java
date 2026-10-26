package com.qlink.storage;

import com.qlink.common.exception.GlobalExceptionHandler;
import com.qlink.features.storage.controller.StorageController;
import com.qlink.features.storage.dto.StoredFileResponse;
import com.qlink.features.storage.enums.*;
import com.qlink.features.storage.service.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StorageStreamingFailureTest {
    private final ch.qos.logback.classic.Logger resolverLog =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                    org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver.class);
    private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> errors =
            new ch.qos.logback.core.read.ListAppender<>();

    @BeforeEach void captureResolverFailures() {
        errors.start();
        resolverLog.addAppender(errors);
    }

    @AfterEach void noSecondarySerializationFailure() {
        resolverLog.detachAppender(errors);
        errors.stop();
        assertThat(errors.list).noneMatch(event -> event.getFormattedMessage().contains("Failure in @ExceptionHandler"));
    }
    private final UUID id = UUID.randomUUID();
    private final StorageService service = mock(StorageService.class);
    private final StorageController controller = new StorageController(service, null, null);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    private final StoredFileResponse metadata = new StoredFileResponse(id, "photo.jpg", "image/jpeg", 100,
            FileCategory.IMAGE, StoredFileStatus.AVAILABLE, Instant.now());

    private InputStream download() {
        var stream = mock(InputStream.class);
        when(service.download(null, id)).thenReturn(new StorageService.Download(metadata, stream));
        return stream;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
        return get("/api/v1/storage/files/{id}/download", id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unusable", "completed", "wrapped", "broken-pipe", "reset"})
    void disconnectedOrCompletedImageDoesNotWriteJson(String failure) throws Exception {
        var stream = download();
        IOException ex = switch (failure) {
            case "unusable" -> new AsyncRequestNotUsableException("Response not usable after response errors");
            case "completed" -> new AsyncRequestNotUsableException("Response not usable after async request completion");
            case "wrapped" -> new IOException("Write failed", new AsyncRequestNotUsableException("Response unusable"));
            case "broken-pipe" -> new IOException("Broken pipe");
            default -> new IOException("Connection reset by peer");
        };
        // Fail on the servlet output path, as a disconnect during transferTo does.
        doAnswer(invocation -> {
            ((OutputStream) invocation.getArgument(0)).write(1);
            return 1L;
        }).when(stream).transferTo(any());
        var interrupted = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters((request, response, chain) -> chain.doFilter(request,
                    new HttpServletResponseWrapper((HttpServletResponse) response) {
                        @Override public jakarta.servlet.ServletOutputStream getOutputStream() throws IOException {
                            throw ex;
                        }
                    })).build();
        var result = interrupted.perform(request()).andReturn();
        assertThat(result.getResolvedException()).isInstanceOf(AsyncRequestNotUsableException.class).hasCause(ex);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(result.getResponse().getContentType()).isNotEqualTo(MediaType.APPLICATION_JSON_VALUE);
        verify(stream).close();
    }

    @Test void committedStorageReadFailurePreservesPartialImageWithoutJson() throws Exception {
        var stream = download();
        doAnswer(invocation -> {
            OutputStream output = invocation.getArgument(0);
            output.write(new byte[] {1, 2, 3});
            output.flush();
            throw new IOException("Disk read failed");
        }).when(stream).transferTo(any());
        var result = mvc.perform(request()).andReturn();
        assertThat(result.getResolvedException()).isInstanceOf(IOException.class);
        assertThat(result.getResponse().isCommitted()).isTrue();
        assertThat(result.getResponse().getContentAsByteArray()).containsExactly(1, 2, 3);
        assertThat(result.getResponse().getContentType()).isNotEqualTo(MediaType.APPLICATION_JSON_VALUE);
        verify(stream).close();
    }

    @Test void bufferedStorageReadFailureCanStillReturnJson() throws Exception {
        var stream = download();
        doAnswer(invocation -> {
            ((OutputStream) invocation.getArgument(0)).write(new byte[] {1, 2, 3});
            throw new IOException("Disk read failed");
        }).when(stream).transferTo(any());
        mvc.perform(request()).andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.code").value("INTERNAL_ERROR"))
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_LENGTH)).isNotEqualTo("100"))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        verify(stream).close();
    }

    @Test void storageUnavailableBeforeStreamingRemainsJson503() throws Exception {
        when(service.download(null, id)).thenThrow(StorageErrors.unavailable());
        mvc.perform(request()).andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.code").value("STORAGE_UNAVAILABLE"));
    }

    @Test void missingFileBeforeStreamingRemainsJson404() throws Exception {
        when(service.download(null, id)).thenThrow(StorageErrors.missing());
        mvc.perform(request()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("FILE_NOT_FOUND"));
    }

    @Test void successfulDownloadKeepsImageHeadersAndBytes() throws Exception {
        var stream = spy(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        var small = new StoredFileResponse(id, "photo.jpg", "image/jpeg", 3,
                FileCategory.IMAGE, StoredFileStatus.AVAILABLE, Instant.now());
        when(service.download(null, id)).thenReturn(new StorageService.Download(small, stream));
        mvc.perform(request()).andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(new byte[] {1, 2, 3}))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "3"))
                .andExpect(header().exists(HttpHeaders.CONTENT_DISPOSITION));
        verify(stream).close();
    }
}
