package com.qlink.features.auth.dto;
import com.qlink.features.auth.enums.AuthProvider;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
public record ProviderAuthRequest(
        @NotNull @Schema(example = "GOOGLE") AuthProvider provider,
        @NotBlank @Size(max = 16384) @Schema(example = "provider-id-token") String idToken,
        @Size(max = 128) @Schema(description = "Raw nonce from /auth/nonce. Required for Apple; Google tokens containing a nonce also require it.") String nonce) {}
