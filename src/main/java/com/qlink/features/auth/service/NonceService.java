package com.qlink.features.auth.service;
import lombok.RequiredArgsConstructor;
import com.qlink.common.exception.ApiException;
import com.qlink.features.auth.dto.*;
import com.qlink.features.auth.entity.NonceChallenge;
import com.qlink.features.auth.enums.AuthProvider;
import com.qlink.features.auth.repository.NonceChallengeRepository;
import com.qlink.security.jwt.TokenHash;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class NonceService {
    private final NonceChallengeRepository challenges;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public NonceResponse issue(AuthProvider provider) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var challenge = new NonceChallenge();
        challenge.setNonceHash(TokenHash.sha256(nonce)); challenge.setProvider(provider);
        challenge.setExpiresAt(Instant.now().plusSeconds(300));
        challenges.save(challenge);
        return new NonceResponse(nonce, challenge.getNonceHash(), challenge.getExpiresAt());
    }
    // Called in the signup/signin transaction, after cryptographic provider verification.
    public void consume(AuthProvider provider, String rawNonce, String tokenNonce) {
        if (provider == AuthProvider.GOOGLE && rawNonce == null && tokenNonce == null) return;
        if (rawNonce == null || tokenNonce == null) throw invalid();
        String hash = TokenHash.sha256(rawNonce);
        String expected = provider == AuthProvider.APPLE ? hash : rawNonce;
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), tokenNonce.getBytes(StandardCharsets.UTF_8))) throw invalid();
        var challenge = challenges.findByNonceHashAndDeletedAtIsNull(hash).orElseThrow(NonceService::invalid);
        if (challenge.getProvider() != provider || challenge.getConsumedAt() != null || !challenge.getExpiresAt().isAfter(Instant.now())) throw invalid();
        challenge.setConsumedAt(Instant.now());
    }
    private static ApiException invalid() { return ApiException.unauthorized("INVALID_NONCE", "Nonce is missing, invalid, expired, or already used"); }
}
