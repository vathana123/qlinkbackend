package com.qlink.features.chat.controller;
import lombok.RequiredArgsConstructor;
import com.qlink.common.response.ApiResponse;
import com.qlink.features.chat.dto.ChatDtos.*;
import com.qlink.features.chat.service.GroupService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat/groups/{roomId}")
@RequiredArgsConstructor
public class GroupController {
    private final GroupService groups;

    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    @PatchMapping
    public ApiResponse<Room> update(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId,@Valid @RequestBody GroupUpdate body) {
        return ApiResponse.ok("Group updated",groups.update(actor(jwt),roomId,body));
    }
    @PatchMapping("/permissions")
    public ApiResponse<Room> permissions(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId,@RequestBody GroupPermissions body) {
        return ApiResponse.ok("Permissions updated",groups.permissions(actor(jwt),roomId,body));
    }
    @PostMapping("/members")
    public ApiResponse<Room> add(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId,@Valid @RequestBody AddMembers body) {
        return ApiResponse.ok("Members added",groups.add(actor(jwt),roomId,body));
    }
    @DeleteMapping("/members/{userId}")
    public ApiResponse<Void> remove(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId,@PathVariable UUID userId) {
        groups.remove(actor(jwt),roomId,userId); return ApiResponse.ok("Member removed",null);
    }
    @PostMapping("/leave")
    public ApiResponse<Void> leave(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId) {
        groups.leave(actor(jwt),roomId); return ApiResponse.ok("Group left",null);
    }
    @PatchMapping("/members/{userId}/role")
    public ApiResponse<Room> role(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId,@PathVariable UUID userId,@Valid @RequestBody ChangeRole body) {
        return ApiResponse.ok("Role updated",groups.role(actor(jwt),roomId,userId,body));
    }
    @PostMapping("/ownership")
    public ApiResponse<Room> transfer(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId,@Valid @RequestBody TransferOwner body) {
        return ApiResponse.ok("Ownership transferred",groups.transfer(actor(jwt),roomId,body));
    }
    @PutMapping("/avatar")
    public ApiResponse<Room> avatar(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId,@Valid @RequestBody GroupAvatar body) {
        return ApiResponse.ok("Avatar updated",groups.avatar(actor(jwt),roomId,body.fileId()));
    }
    @DeleteMapping("/avatar")
    public ApiResponse<Room> removeAvatar(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId) {
        return ApiResponse.ok("Avatar removed",groups.avatar(actor(jwt),roomId,null));
    }
    @DeleteMapping
    public ApiResponse<Void> delete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId) {
        groups.delete(actor(jwt),roomId); return ApiResponse.ok("Group deleted",null);
    }
}
