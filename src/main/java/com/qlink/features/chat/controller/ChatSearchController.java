package com.qlink.features.chat.controller;
import lombok.RequiredArgsConstructor;

import com.qlink.common.response.ApiResponse;
import com.qlink.features.chat.dto.ChatDtos.*;
import com.qlink.features.chat.service.ChatSearchService;
import com.qlink.features.chat.service.ChatSearchService.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class ChatSearchController {
    private final ChatSearchService search;

    @GetMapping("/search/rooms")
    public ApiResponse<Results<Room>> rooms(@AuthenticationPrincipal Jwt jwt, @RequestParam String q,
            @RequestParam(required=false) String cursor, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok("Conversations", search.rooms(UUID.fromString(jwt.getSubject()), q, cursor, size));
    }
    @GetMapping({"/search/messages", "/rooms/{roomId}/search"})
    public ApiResponse<Results<Match>> messages(@AuthenticationPrincipal Jwt jwt,
            @PathVariable(required=false) UUID roomId, @RequestParam String q,
            @RequestParam(required=false) UUID senderId, @RequestParam(required=false) Instant from,
            @RequestParam(required=false) Instant to, @RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok("Messages", search.messages(UUID.fromString(jwt.getSubject()), roomId, q, senderId, from, to, cursor, size));
    }
    @GetMapping("/rooms/{roomId}/messages/{messageId}/context")
    public ApiResponse<Context> context(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId,
            @PathVariable UUID messageId, @RequestParam(defaultValue="15") int size) {
        return ApiResponse.ok("Message context", search.context(UUID.fromString(jwt.getSubject()), roomId, messageId, size));
    }
    @GetMapping("/rooms/{roomId}/messages/newer")
    public ApiResponse<History> newer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId,
            @RequestParam String cursor, @RequestParam(defaultValue="30") int size) {
        return ApiResponse.ok("Newer messages", search.newer(UUID.fromString(jwt.getSubject()), roomId, cursor, size));
    }
}
