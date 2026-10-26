package com.qlink.features.auth.controller;
import lombok.RequiredArgsConstructor;
import com.qlink.common.response.ApiResponse;
import com.qlink.features.auth.dto.*;
import com.qlink.features.auth.service.*;
import com.qlink.features.user.dto.UserResponse;
import com.qlink.features.user.service.UserService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Google and Apple ID-token authentication; application JWT sessions")
@RequiredArgsConstructor
public class AuthController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);
    private final AuthService auth;
    private final UserService users;
    private final NonceService nonces;

    @PostMapping("/nonce")
    @Operation(summary = "Create a five-minute single-use provider nonce",
            description = "Apple: pass nonceHash to the provider SDK and raw nonce to signup/signin. Google: pass raw nonce to both.")
    public ApiResponse<NonceResponse> nonce(@Valid @RequestBody NonceRequest request) {
        return ApiResponse.ok("Nonce created", nonces.issue(request.provider()));
    }
    @PostMapping("/signup") @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new provider identity", description = "An existing identity returns 409. Matching email addresses never merge accounts.")
    public ApiResponse<AuthResponse> signup(@Valid @RequestBody ProviderAuthRequest request) {
        log.info("Authentication endpoint=POST /api/v1/auth/signup provider={} idTokenReceived={}", request.provider(), request.idToken() != null && !request.idToken().isBlank());
        return ApiResponse.ok("Registration successful", auth.signup(request));
    }
    @PostMapping("/signin")
    @Operation(summary = "Sign in with a registered provider identity", description = "An unregistered identity returns 404.")
    public ApiResponse<AuthResponse> signin(@Valid @RequestBody ProviderAuthRequest request) {
        log.info("Authentication endpoint=POST /api/v1/auth/signin provider={} idTokenReceived={}", request.provider(), request.idToken() != null && !request.idToken().isBlank());
        return ApiResponse.ok("Sign in successful", auth.signin(request));
    }
    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh token", description = "Use each refresh token once. Reuse revokes the entire session; serialize refresh requests on the client.")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok("Token refreshed", auth.refresh(request.refreshToken()));
    }
    @PostMapping("/signout") @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Revoke the current application session", description = "Subsequent REST and WebSocket operations reject this session immediately. Other sessions and provider credentials are not revoked.")
    public ApiResponse<Void> signout(@AuthenticationPrincipal Jwt jwt) {
        auth.signout(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")));
        return ApiResponse.ok("Signed out", null);
    }
    @GetMapping("/me") @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get the authenticated user profile")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok("Current user", users.me(UUID.fromString(jwt.getSubject())));
    }
}
