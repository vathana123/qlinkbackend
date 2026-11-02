package com.qlink.features.chat.realtime;

import com.qlink.common.response.ApiResponse;
import com.qlink.features.auth.service.SessionAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController @RequiredArgsConstructor
public class ActivityController {
    public record Watch(List<UUID> userIds) {}
    public record TypingRequest(UUID roomId, boolean typing) {}
    private final ActivityService activity;
    private final SessionAuthorization sessions;
    @GetMapping("/api/v1/users/{userId}/presence")
    public ApiResponse<ActivityService.Presence> presence(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok("Presence", activity.presence(sessions.actor(jwt), userId));
    }
    @MessageMapping("/activity/watch")
    public void watch(Principal principal, @Header("simpSessionId") String session, @Payload Watch request) {
        activity.watch(session, sessions.recheck(((JwtAuthenticationToken) principal).getToken()), request.userIds());
    }
    @MessageMapping("/activity/typing")
    public void typing(Principal principal, @Header("simpSessionId") String session, @Payload TypingRequest request) {
        activity.typing(session, sessions.recheck(((JwtAuthenticationToken) principal).getToken()), request.roomId(), request.typing());
    }
}
