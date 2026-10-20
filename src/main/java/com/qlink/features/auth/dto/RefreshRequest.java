package com.qlink.features.auth.dto;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
public record RefreshRequest(@NotBlank @Size(max = 8192) @Schema(example = "application-refresh-jwt") String refreshToken) {}
