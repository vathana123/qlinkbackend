package com.qlink.features.user.dto;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
public record ProfileRequest(
    @Size(min=1,max=255) @Pattern(regexp="(?s).*\\S.*", message="Display name must not be blank") String displayName,
    @Schema(description="3–30 letters, numbers or underscores; normalized to lowercase. Omitted/null fields are unchanged.") String username) {}
