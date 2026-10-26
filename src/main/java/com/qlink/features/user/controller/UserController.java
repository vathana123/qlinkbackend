package com.qlink.features.user.controller;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class UserController {
    private final DiscoveryService service;
    private final com.qlink.features.user.service.ProfileService profile;

    @PatchMapping("/me") @Operation(summary="Update your display name and/or username", description="Null or omitted fields stay unchanged. Usernames are case-insensitively unique; conflicts return 409.")
    public ApiResponse<UserResponse> profile(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileRequest request) {
        return ApiResponse.ok("Profile saved",profile.update(jwt,request));
    }
    @PutMapping(value="/me/avatar", consumes="multipart/form-data")
    @Operation(summary="Replace your avatar", description="Multipart file: JPEG, PNG, GIF or WebP. Existing storage content and size validation applies. Avatars are visible to authenticated users.")
    public ApiResponse<UserResponse> avatar(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return ApiResponse.ok("Avatar saved",profile.avatar(jwt,file));
    }
    @DeleteMapping("/me/avatar") @Operation(summary="Remove your avatar")
    public ApiResponse<UserResponse> removeAvatar(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok("Avatar removed",profile.avatar(jwt,null));
    }
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
