package com.qlink.features.user.dto;
import java.util.UUID;
public record PublicUserResponse(UUID id, String username, String displayName, String avatarUrl) {}
