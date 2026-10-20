package com.qlink.security.oauth;
public record ProviderIdentity(String subject, String email, String displayName, String avatarUrl, String nonce) {}
