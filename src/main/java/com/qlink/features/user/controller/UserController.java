package com.qlink.features.user.controller;
import com.qlink.common.response.ApiResponse;
import com.qlink.features.user.dto.*;
import com.qlink.features.user.service.DiscoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.data.domain.Page;
import java.util.UUID;
@RestController @RequestMapping("/api/v1/users") @SecurityRequirement(name="bearerAuth")
public class UserController {
    private final DiscoveryService service;
    public UserController(DiscoveryService service) { this.service=service; }
    @GetMapping("/search") @Operation(summary="Find users by name, username, exact email or international phone number")
    public ApiResponse<Page<PublicUserResponse>> search(@AuthenticationPrincipal Jwt jwt, @RequestParam String q, jakarta.servlet.http.HttpServletResponse response,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok("Users",service.search(UUID.fromString(jwt.getSubject()),q,page,size));
    }
    @GetMapping("/{userId}/public") @Operation(summary="Resolve a QR user discovery identifier")
    public ApiResponse<PublicUserResponse> resolve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID userId) {
        return ApiResponse.ok("User",service.resolve(UUID.fromString(jwt.getSubject()),userId));
    }
    @PatchMapping("/me/phone-number") @Operation(summary="Set or remove an optional unverified discovery phone number")
    public ApiResponse<Void> phone(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody PhoneNumberRequest request) {
        service.updatePhone(UUID.fromString(jwt.getSubject()), request.phoneNumber());
        return ApiResponse.ok("Discovery phone number saved; ownership is not verified", null);
    }
    @PatchMapping("/me/username") @Operation(summary="Set or change your discovery username")
    public ApiResponse<UserResponse> update(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody UsernameRequest request) {
        return ApiResponse.ok("Username saved",service.update(UUID.fromString(jwt.getSubject()),request.username()));
    }
}
