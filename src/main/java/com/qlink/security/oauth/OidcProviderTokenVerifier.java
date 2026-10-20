package com.qlink.security.oauth;
import com.qlink.common.exception.ApiException;
import com.qlink.features.auth.enums.AuthProvider;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import java.time.Instant;
import java.util.*;

@Component
public class OidcProviderTokenVerifier implements ProviderTokenVerifier {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OidcProviderTokenVerifier.class);
    private static final Set<String> CATEGORIES = Set.of("provider_configuration", "issuer", "audience", "authorized_party", "expiration", "subject", "issued_at", "not_before");
    private final Map<AuthProvider, JwtDecoder> decoders;
    @org.springframework.beans.factory.annotation.Autowired
    public OidcProviderTokenVerifier(OAuthProperties properties) {
        this(Map.of(
                AuthProvider.GOOGLE, build("https://www.googleapis.com/oauth2/v3/certs", AuthProvider.GOOGLE, properties.googleClientIds(), properties.googleAndroidClientIds()),
                AuthProvider.APPLE, build("https://appleid.apple.com/auth/keys", AuthProvider.APPLE, properties.appleClientIds(), List.of())));
    }
    // Allows offline signature/claim tests with locally generated provider keys.
    OidcProviderTokenVerifier(Map<AuthProvider, JwtDecoder> decoders) { this.decoders = Map.copyOf(decoders); }
    private static JwtDecoder build(String uri, AuthProvider provider, List<String> audiences, List<String> androidClientIds) {
        boolean configured = audiences != null && audiences.stream().anyMatch(a -> !a.isBlank());
        log.info("OAuth configuration provider={} audienceConfigured={}", provider, configured);
        if (!configured) {
            log.warn("OAuth provider={} category=provider_configuration message=No accepted client IDs configured", provider);
            return token -> { throw new JwtValidationException("Provider is not configured", List.of(new OAuth2Error("provider_configuration"))); };
        }
        var decoder = NimbusJwtDecoder.withJwkSetUri(uri).build(); // RS256 only; never use a token-supplied key URL.
        decoder.setJwtValidator(validator(provider, audiences, androidClientIds));
        return decoder;
    }
    static OAuth2TokenValidator<Jwt> validator(AuthProvider provider, List<String> audiences) {
        return validator(provider, audiences, List.of());
    }
    static OAuth2TokenValidator<Jwt> validator(AuthProvider provider, List<String> audiences, List<String> androidClientIds) {
        Set<String> trustedParties = new HashSet<>();
        if (audiences != null) audiences.stream().filter(a -> !a.isBlank()).forEach(trustedParties::add);
        if (provider == AuthProvider.GOOGLE && androidClientIds != null)
            androidClientIds.stream().filter(a -> !a.isBlank()).forEach(trustedParties::add);
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            var issuer = jwt.getClaimAsString("iss");
            boolean issuerOk = provider == AuthProvider.GOOGLE
                    ? Set.of("https://accounts.google.com", "accounts.google.com").contains(issuer == null ? "" : issuer)
                    : "https://appleid.apple.com".equals(issuer);
            String azp = jwt.getClaimAsString("azp");
            List<String> tokenAudiences = jwt.getAudience() == null ? List.of() : jwt.getAudience();
            boolean audienceOk = audiences != null && tokenAudiences.stream().anyMatch(a -> !a.isBlank() && audiences.contains(a));
            boolean azpOk = (azp == null && tokenAudiences.size() == 1) || (azp != null && trustedParties.contains(azp));
            List<OAuth2Error> errors = new ArrayList<>();
            if (audiences == null || audiences.stream().noneMatch(a -> !a.isBlank())) errors.add(new OAuth2Error("provider_configuration"));
            if (!issuerOk) errors.add(new OAuth2Error("issuer"));
            if (!audienceOk) errors.add(new OAuth2Error("audience"));
            if (!azpOk) errors.add(new OAuth2Error("authorized_party"));
            if (jwt.getSubject() == null || jwt.getSubject().isBlank() || jwt.getSubject().length() > 255) errors.add(new OAuth2Error("subject"));
            if (jwt.getExpiresAt() == null || Instant.now().minusSeconds(30).isAfter(jwt.getExpiresAt())) errors.add(new OAuth2Error("expiration"));
            if (jwt.getIssuedAt() == null || jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(60))) errors.add(new OAuth2Error("issued_at"));
            if (jwt.getNotBefore() != null && Instant.now().plusSeconds(30).isBefore(jwt.getNotBefore())) errors.add(new OAuth2Error("not_before"));
            if (provider == AuthProvider.GOOGLE) {
                // Nimbus calls validators only after signature verification. Do not log other claims.
                log.info("Google client validation signatureVerified=true aud={} azp={} issuerValid={} audienceValid={} authorizedPartyValid={} expirationValid={}",
                        tokenAudiences.stream().limit(10).map(OidcProviderTokenVerifier::safeClientId).toList(),
                        safeClientId(azp), issuerOk, audienceOk, azpOk,
                        errors.stream().noneMatch(e -> e.getErrorCode().equals("expiration")));
            }
            return errors.isEmpty() ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(errors);
        };
        return claims;
    }
    @Override
    public ProviderIdentity verify(AuthProvider provider, String token) {
        try {
            if (token == null || token.isBlank()) throw new IllegalArgumentException("Missing ID token");
            Jwt jwt = decoders.get(provider).decode(token);
            Object verified = jwt.getClaims().get("email_verified");
            String email = Boolean.TRUE.equals(verified) || "true".equals(verified) ? bounded(jwt.getClaimAsString("email"), 320) : null;
            var identity = new ProviderIdentity(jwt.getSubject(), email, bounded(jwt.getClaimAsString("name"), 255),
                    bounded(jwt.getClaimAsString("picture"), 2048), jwt.getClaimAsString("nonce"));
            log.info("OAuth verification provider={} succeeded=true", provider);
            return identity;
        } catch (JwtException | IllegalArgumentException ex) {
            // Only controlled labels are logged: decoder messages can contain claims or token text.
            log.warn("OAuth verification provider={} succeeded=false category={} exceptionType={} message=Provider verification rejected",
                    provider, failureCategory(ex, token), ex.getClass().getSimpleName());
            throw ApiException.unauthorized("INVALID_PROVIDER_TOKEN", "Provider token is invalid or expired");
        }
    }
    private static String failureCategory(Exception ex, String token) {
        if (token == null || token.isBlank()) return "missing_id_token";
        if (ex instanceof JwtValidationException validation) {
            return validation.getErrors().stream().map(OAuth2Error::getErrorCode)
                    .filter(CATEGORIES::contains).sorted().distinct().collect(java.util.stream.Collectors.joining(","));
        }
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.nimbusds.jose.RemoteKeySourceException) return "provider_keys_unavailable";
            if (cause instanceof com.nimbusds.jose.proc.BadJWSException) return "signature_or_signing_key";
        }
        return "malformed_token_or_claims";
    }
    private static String bounded(String value, int max) { return value != null && value.length() <= max ? value : null; }
    private static String safeClientId(String value) {
        if (value == null) return "absent";
        return value.length() <= 255 && value.matches("[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com")
                ? value : "redacted_non_client_id";
    }
}
