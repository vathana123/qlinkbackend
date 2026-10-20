package com.qlink.features.chat.controller;
import com.qlink.common.response.ApiResponse;
import com.qlink.features.chat.service.ChatService;
import com.qlink.features.user.dto.PublicUserResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.data.domain.Page;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/contacts")
public class ContactsController {
 private final ChatService service;
 public ContactsController(ChatService service) {this.service=service;}
 @GetMapping
 public ApiResponse<Page<PublicUserResponse>> contacts(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
  return ApiResponse.ok("Contacts",service.contacts(UUID.fromString(jwt.getSubject()),page,size));
 }
}
