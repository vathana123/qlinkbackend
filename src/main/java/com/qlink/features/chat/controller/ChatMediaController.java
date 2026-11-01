package com.qlink.features.chat.controller;

import com.qlink.common.response.ApiResponse;
import com.qlink.features.chat.dto.ChatDtos.History;
import com.qlink.features.chat.service.ChatMediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat/rooms/{roomId}/media")
@RequiredArgsConstructor
public class ChatMediaController {
    private final ChatMediaService service;
    @GetMapping
    public ResponseEntity<ApiResponse<History>> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId,
        @RequestParam String category, @RequestParam(required=false) String cursor, @RequestParam(defaultValue="30") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok("Shared media",
            service.list(UUID.fromString(jwt.getSubject()), roomId, category, cursor, size)));
    }
}
