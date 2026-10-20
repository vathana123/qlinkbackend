package com.qlink.features.auth.dto;
import com.qlink.features.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;
public record AuthResponse(
        @Schema(example = "<signed-access-jwt>") String accessToken,
        @Schema(example = "<signed-refresh-jwt>") String refreshToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(example = "900") long expiresIn,
        UserResponse user) {}
