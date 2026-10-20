package com.qlink.features.user.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record UsernameRequest(@NotBlank @Size(max = 30) String username) {}
