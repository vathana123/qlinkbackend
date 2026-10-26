package com.qlink.features.auth.service;
import com.qlink.common.exception.ApiException;
import com.qlink.features.auth.dto.*;
import com.qlink.features.auth.entity.*;
import com.qlink.features.auth.repository.*;
import com.qlink.features.user.entity.User;
import com.qlink.features.user.repository.UserRepository;
import com.qlink.features.user.mapper.UserMapper;
import com.qlink.features.user.service.UserService;
import com.qlink.security.jwt.*;
import com.qlink.security.oauth.ProviderTokenVerifier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {
    private final com.qlink.features.push.PushDeviceRepository pushDevices;
    private final ProviderTokenVerifier verifier;
    private final NonceService nonces;
    private final UserRepository users;
    private final OAuthAccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final UserService userService;
    private final UserMapper mapper;
    private final TokenService tokens;
    private final JwtProperties properties;
    private final JwtDecoder refreshDecoder;

    public AuthService(ProviderTokenVerifier verifier, NonceService nonces, UserRepository users,
                       OAuthAccountRepository accounts, AuthSessionRepository sessions, RefreshTokenRepository refreshTokens,
                       UserService userService, UserMapper mapper, TokenService tokens, JwtProperties properties,
                       @Qualifier("refreshJwtDecoder") JwtDecoder refreshDecoder,
                       com.qlink.features.push.PushDeviceRepository pushDevices) {
        this.pushDevices = pushDevices;
        this.verifier = verifier; this.nonces = nonces; this.users = users; this.accounts = accounts;
        this.sessions = sessions; this.refreshTokens = refreshTokens; this.userService = userService;
        this.mapper = mapper; this.tokens = tokens; this.properties = properties; this.refreshDecoder = refreshDecoder;
    }
    @Transactional
    public AuthResponse signup(ProviderAuthRequest request) {
        var identity = verifier.verify(request.provider(), request.idToken());
        nonces.consume(request.provider(), request.nonce(), identity.nonce());
        if (accounts.findByProviderAndProviderSubject(request.provider(), identity.subject()).isPresent())
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REGISTERED", "Provider identity is already registered");
        var user = new User();
        user.setEmail(identity.email()); user.setDisplayName(identity.displayName()); user.setAvatarUrl(identity.avatarUrl());
        users.save(user);
        var account = new OAuthAccount();
        account.setUser(user); account.setProvider(request.provider());
        account.setProviderSubject(identity.subject()); account.setProviderEmail(identity.email());
        accounts.save(account);
        return newSession(user);
    }
    @Transactional
    public AuthResponse signin(ProviderAuthRequest request) {
        var identity = verifier.verify(request.provider(), request.idToken());
        nonces.consume(request.provider(), request.nonce(), identity.nonce());
        var account = accounts.findByProviderAndProviderSubject(request.provider(), identity.subject())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_REGISTERED", "Provider identity is not registered"));
        var user = userService.requireActive(account.getUser().getId());
        user.recordActor(user.getId()); account.recordActor(user.getId());
        if (identity.email() != null) account.setProviderEmail(identity.email());
        return newSession(user);
    }
    private AuthResponse newSession(User user) {
        var session = new AuthSession();
        session.setUserId(user.getId()); session.setExpiresAt(Instant.now().plus(properties.refreshTtl()));
        session.recordActor(user.getId()); sessions.save(session);
        return issue(user, session, null);
    }
    // Reuse must commit the session revocation even though the endpoint returns 401.
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse refresh(String rawToken) {
        Jwt jwt;
        try { jwt = refreshDecoder.decode(rawToken); }
        catch (JwtException | IllegalArgumentException ex) { throw invalidRefresh(); }
        var session = sessions.findLockedById(UUID.fromString(jwt.getClaimAsString("sid"))).orElseThrow(AuthService::invalidRefresh);
        if (!session.getUserId().toString().equals(jwt.getSubject())) throw invalidRefresh();
        if (session.getRevokedAt() != null) throw ApiException.unauthorized("REVOKED_REFRESH_TOKEN", "Session has been revoked");
        if (!session.getExpiresAt().isAfter(Instant.now())) throw invalidRefresh();
        var previous = refreshTokens.findByTokenHash(TokenHash.sha256(rawToken)).orElseThrow(AuthService::invalidRefresh);
        if (!session.getId().equals(previous.getSessionId())) throw invalidRefresh();
        if (previous.getRevokedAt() != null) {
            session.setRevokedAt(Instant.now()); session.recordActor(session.getUserId());pushDevices.deleteBySessionId(session.getId());
            throw ApiException.unauthorized("REFRESH_TOKEN_REUSED", "Refresh token reuse detected; sign in again");
        }
        if (!previous.getExpiresAt().isAfter(Instant.now())) throw invalidRefresh();
        var user = userService.requireActive(session.getUserId());
        previous.setRevokedAt(Instant.now()); previous.recordActor(user.getId());
        session.recordActor(user.getId());
        return issue(user, session, previous);
    }
    private AuthResponse issue(User user, AuthSession session, RefreshToken previous) {
        // Fixed session expiry prevents rotation from extending a session indefinitely.
        String refresh = tokens.refresh(user.getId(), session.getId(), session.getExpiresAt());
        var record = new RefreshToken();
        record.setSessionId(session.getId()); record.setTokenHash(TokenHash.sha256(refresh));
        record.setExpiresAt(session.getExpiresAt()); record.recordActor(user.getId());
        refreshTokens.save(record);
        if (previous != null) previous.setReplacedBy(record.getId());
        return new AuthResponse(tokens.access(user.getId(), session.getId()), refresh, "Bearer",
                properties.accessTtl().toSeconds(), mapper.toResponse(user));
    }
    @Transactional
    public void signout(UUID userId, UUID sessionId) {
        var session = sessions.findLockedById(sessionId).orElseThrow(AuthService::invalidRefresh);
        if (!session.getUserId().equals(userId)) throw invalidRefresh();
        session.setRevokedAt(Instant.now()); session.recordActor(userId);pushDevices.deleteBySessionId(sessionId);
    }
    private static ApiException invalidRefresh() {
        return ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired");
    }
}
