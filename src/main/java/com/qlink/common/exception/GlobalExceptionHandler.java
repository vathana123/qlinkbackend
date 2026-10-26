package com.qlink.common.exception;
import com.qlink.common.response.ApiError;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.*;
import java.util.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiError.of(ex.getCode(), ex.getMessage()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        if (ex.getBindingResult().getTarget() instanceof com.qlink.features.auth.dto.ProviderAuthRequest request) {
            log.warn("Authentication endpoint={} provider={} idTokenReceived={} category=request_validation exceptionType=MethodArgumentNotValidException message=Invalid authentication request",
                    ex.getParameter().getMethod().getName(), request.provider(), request.idToken() != null && !request.idToken().isBlank());
        }
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError(false, "Validation failed", new ApiError.Details("VALIDATION_ERROR", fields)));
    }
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> invalidParameter() {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", "Invalid or missing request parameter"));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge() {
        return ResponseEntity.status(413).body(ApiError.of("FILE_TOO_LARGE", "Upload exceeds the configured maximum size"));
    }
    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.multipart.MultipartException.class})
    ResponseEntity<ApiError> invalidMultipart() {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", "Invalid or missing multipart file"));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformed() {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", "Malformed request or unsupported value"));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict() {
        return ResponseEntity.status(409).body(ApiError.of("CONFLICT", "Identity already registered or conflicting data"));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing() {
        return ResponseEntity.status(404).body(ApiError.of("NOT_FOUND", "Resource not found"));
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method() {
        return ResponseEntity.status(405).body(ApiError.of("METHOD_NOT_ALLOWED", "Method not allowed"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex, HttpServletResponse response) {
        // Includes AsyncRequestNotUsableException, even before the servlet marks
        // the response committed. Never try to write another body in this case.
        if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            log.debug("Client response is no longer usable");
            return null;
        }
        // Do not log token-bearing request bodies or exception messages.
        log.error("Unhandled API failure of type {}", ex.getClass().getName());
        if (response.isCommitted()) return null;
        // Streaming may have selected a media type and buffered some bytes.
        // Retain security headers but discard that body and its file metadata.
        response.resetBuffer();
        response.setContentLengthLong(-1);
        response.setHeader(HttpHeaders.CONTENT_LENGTH, null);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, null);
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
