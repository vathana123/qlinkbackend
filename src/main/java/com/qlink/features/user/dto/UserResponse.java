package com.qlink.features.user.dto;
import com.qlink.features.user.enums.UserStatus;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;
public record UserResponse(
        @Schema(example = "4bf568db-6a87-4d84-8a12-3e21ada11646") UUID id,
        @Schema(example = "Example User") String displayName,
        @Schema(example = "user@example.com") String email,
        String avatarUrl, String username, UserStatus status) {}
