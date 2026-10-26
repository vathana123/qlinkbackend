package com.qlink.features.chat.controller;
import lombok.RequiredArgsConstructor;
import com.qlink.common.response.ApiResponse;
import com.qlink.features.chat.dto.ChatDtos.*;
import com.qlink.features.chat.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.data.domain.Page;
import java.util.UUID;
@RestController @RequestMapping("/api/v1/chat") @SecurityRequirement(name="bearerAuth")
@RequiredArgsConstructor
public class ChatController {
 private final ChatService service;

 @PostMapping("/groups")
 public ApiResponse<Room> createGroup(@AuthenticationPrincipal Jwt jwt,@RequestBody CreateGroup request) {
  return ApiResponse.ok("Group created",service.createGroup(viewer(jwt),request));
 }
 @GetMapping("/groups/{roomId}")
 public ApiResponse<Room> group(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId) {
  return ApiResponse.ok("Group",service.group(viewer(jwt),roomId));
 }
 @GetMapping("/groups/{roomId}/members")
 public ApiResponse<java.util.List<GroupMember>> members(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId) {
  return ApiResponse.ok("Members",service.groupMembers(viewer(jwt),roomId));
 }
 @GetMapping("/rooms/{roomId}")
 public ApiResponse<Room> room(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId){return ApiResponse.ok("Room",service.room(viewer(jwt),roomId));}
 @GetMapping("/messages/client/{key}")
 public ApiResponse<Sent> sentByClient(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID key) {
  return ApiResponse.ok("Committed message",service.sentByClient(viewer(jwt),key));
 }
 @PostMapping("/rooms/{roomId}/receipts")
 public ApiResponse<java.util.List<Message>> receipts(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId,@Valid @RequestBody Receipts request) {
  return ApiResponse.ok("Receipts",service.acknowledge(viewer(jwt),roomId,request));
 }
 @PatchMapping("/rooms/{roomId}/messages/{id}")
 public ApiResponse<Message> edit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId,@PathVariable UUID id,@Valid @RequestBody Edit request) {
  return ApiResponse.ok("Message edited",service.edit(viewer(jwt),roomId,id,request));
 }
 @DeleteMapping("/rooms/{roomId}/messages/{id}")
 public ApiResponse<Message> delete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId,@PathVariable UUID id,@RequestParam(defaultValue="false") boolean everyone) {
  return ApiResponse.ok("Message deleted",service.delete(viewer(jwt),roomId,id,everyone));
 }
 private UUID viewer(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 @GetMapping("/personal/with/{userId}") @Operation(summary="Find a personal room without creating it")
 public ApiResponse<Lookup> lookup(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID userId) {
  return ApiResponse.ok("Conversation",service.lookup(viewer(jwt),userId));
 }
 @PostMapping("/personal/messages") @Operation(summary="Atomically send the first message or reuse an active room")
 public ApiResponse<Sent> personal(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody PersonalSend request) {
  return ApiResponse.ok("Message sent",service.sendPersonal(viewer(jwt),request));
 }
 @GetMapping("/rooms") @Operation(summary="List your active rooms")
 public ApiResponse<Page<Room>> rooms(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
  return ApiResponse.ok("Rooms",service.list(viewer(jwt),page,size));
 }
 @GetMapping("/rooms/{roomId}/messages") @Operation(summary="Read messages newest first using an opaque cursor")
 public ApiResponse<History> history(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="30") int size) {
  return ApiResponse.ok("Messages",service.history(viewer(jwt),roomId,cursor,size));
 }
 @PostMapping("/rooms/{roomId}/messages") @Operation(summary="Send an idempotent text message as the authenticated member")
 public ApiResponse<Sent> send(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID roomId,@Valid @RequestBody Send request) {
  return ApiResponse.ok("Message sent",service.sendExisting(viewer(jwt),roomId,request));
 }
}
