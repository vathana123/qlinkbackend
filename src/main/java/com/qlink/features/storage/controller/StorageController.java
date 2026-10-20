package com.qlink.features.storage.controller;

import com.qlink.features.storage.service.StorageService;
import com.qlink.features.storage.dto.StoredFileResponse;
import com.qlink.features.storage.enums.FileCategory;
import com.qlink.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/storage/files")
@SecurityRequirement(name="bearerAuth")
public class StorageController {
    private final StorageService service;
    private final com.qlink.features.storage.config.StorageProperties properties;
    private final com.qlink.features.storage.service.FileContentPolicy policy;
    public StorageController(StorageService service, com.qlink.features.storage.config.StorageProperties properties,
            com.qlink.features.storage.service.FileContentPolicy policy) {
        this.service=service; this.properties=properties; this.policy=policy;
    }
    @GetMapping("/policy")
    public ApiResponse<java.util.Map<String,Object>> policy() {
        return ApiResponse.ok("Upload policy",java.util.Map.of("maxSizeBytes",properties.maxFileSize().toBytes(),"extensions",policy.extensions()));
    }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary="Upload a private file", description="Multipart file field required; optional category must match detected content. Example multipart parts: file=@photo.png and category=IMAGE. Default maximum 25 MiB, configurable. Allowed: JPEG, PNG, GIF, WebP; MP3, WAV, FLAC, Ogg/Opus, M4A; MP4, MOV, WebM; PDF, DOC/DOCX, XLS/XLSX, PPT/PPTX, TXT, CSV, JSON. Content is detected independently of client MIME; filename extension must match. Generic archives, HTML, SVG, executables and scripts are not supported.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="413",description="File or multipart request exceeds configured size"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="415",description="Unsupported content or extension"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="503",description="Storage unavailable")
    })
    public ResponseEntity<ApiResponse<StoredFileResponse>> upload(@AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file, @RequestParam(required=false) FileCategory category, @RequestParam(defaultValue="false") boolean voice) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok("File uploaded",service.upload(jwt,file,category,voice)));
    }
    @GetMapping("/{fileId}") @Operation(summary="Get private file metadata (owner or authorized message participant)")
    public ResponseEntity<ApiResponse<StoredFileResponse>> metadata(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID fileId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok("File",service.metadata(jwt,fileId)));
    }
    @GetMapping("/{fileId}/download") @Operation(summary="Download a private file as an attachment (owner or authorized message participant)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Private attachment; Content-Type and Content-Length reflect stored metadata",
        content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/octet-stream",
            schema=@io.swagger.v3.oas.annotations.media.Schema(type="string",format="binary")))
    public void download(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID fileId,HttpServletResponse response) throws IOException {
        // Synchronous bounded-buffer streaming keeps stream ownership explicit, including disconnects.
        try (var download=service.download(jwt,fileId)) {
            var file=download.metadata();
            response.setContentType(file.contentType());
            response.setContentLengthLong(file.sizeBytes());
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(file.originalName(),StandardCharsets.UTF_8).build().toString());
            response.setHeader("X-Content-Type-Options","nosniff");
            response.setHeader("Cache-Control","no-store");
            download.stream().transferTo(response.getOutputStream());
        }
    }
    @DeleteMapping("/{fileId}") @Operation(summary="Soft-delete an owned file; repeating retries physical cleanup")
    public ApiResponse<Void> delete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID fileId) {
        service.delete(jwt,fileId); return ApiResponse.ok("File deleted",null);
    }
}
