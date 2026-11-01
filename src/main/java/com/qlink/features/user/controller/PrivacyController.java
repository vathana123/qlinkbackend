package com.qlink.features.user.controller;
import com.qlink.common.response.ApiResponse;
import com.qlink.features.auth.service.SessionAuthorization;
import com.qlink.features.user.service.PrivacyService;
import com.qlink.features.user.dto.PublicUserResponse;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.UUID;
@RestController @RequestMapping("/api/v1/users") @RequiredArgsConstructor
public class PrivacyController {
    private final PrivacyService privacy; private final SessionAuthorization sessions;
    @ModelAttribute public void noCache(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @GetMapping("/me/privacy") public ApiResponse<PrivacyService.Settings> settings(@AuthenticationPrincipal Jwt jwt) { return ApiResponse.ok("Privacy",privacy.settings(sessions.actor(jwt))); }
    @PutMapping("/me/privacy") public ApiResponse<PrivacyService.Settings> update(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody PrivacyService.Settings settings) { return ApiResponse.ok("Privacy saved",privacy.update(sessions.actor(jwt),settings)); }
    @GetMapping("/{id}/relationship") public ApiResponse<PrivacyService.Relationship> relationship(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return ApiResponse.ok("Permissions",privacy.relationship(sessions.actor(jwt),id)); }
    @PutMapping("/me/blocks/{id}") public ApiResponse<PrivacyService.Relationship> block(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return ApiResponse.ok("User blocked",privacy.block(sessions.actor(jwt),id,true)); }
    @DeleteMapping("/me/blocks/{id}") public ApiResponse<PrivacyService.Relationship> unblock(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return ApiResponse.ok("User unblocked",privacy.block(sessions.actor(jwt),id,false)); }
    @GetMapping("/me/blocks") public ApiResponse<org.springframework.data.domain.Page<PublicUserResponse>> blocked(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return ApiResponse.ok("Blocked users",privacy.blocked(sessions.actor(jwt),page,size)); }
}
