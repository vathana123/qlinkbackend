package com.qlink.features.auth.dto;
import java.time.Instant;
public record NonceResponse(String nonce, String nonceHash, Instant expiresAt) {}
