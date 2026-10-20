package com.qlink.features.auth.dto;
import jakarta.validation.constraints.NotNull;
import com.qlink.features.auth.enums.AuthProvider;
public record NonceRequest(@NotNull AuthProvider provider) {}
