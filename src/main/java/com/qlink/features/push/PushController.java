package com.qlink.features.push;
import lombok.RequiredArgsConstructor;
import com.qlink.common.response.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
@RestController @RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PushController {
 private final PushService push;
 private final org.springframework.context.ApplicationEventPublisher events;
 public record Registration(@NotBlank @Size(max=4096) String token,@NotBlank String platform){}
 public record Mute(boolean muted){}
 @PutMapping("/push/devices/{installation}") public ApiResponse<Void> register(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID installation,@Valid @RequestBody Registration request){push.register(UUID.fromString(jwt.getSubject()),UUID.fromString(jwt.getClaimAsString("sid")),installation,request.token(),request.platform());return ApiResponse.ok("Device registered",null);}
 @DeleteMapping("/push/devices/{installation}") public ApiResponse<Void> unregister(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID installation){push.unregister(UUID.fromString(jwt.getSubject()),UUID.fromString(jwt.getClaimAsString("sid")),installation);return ApiResponse.ok("Device removed",null);}
 @GetMapping("/chat/rooms/{room}/notifications") public ApiResponse<Mute> preference(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID room){return ApiResponse.ok("Notifications",new Mute(push.muted(UUID.fromString(jwt.getSubject()),room)));}
 @org.springframework.transaction.annotation.Transactional
 @PutMapping("/chat/rooms/{room}/notifications") public ApiResponse<Void> mute(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID room,@RequestBody Mute request){var actor=UUID.fromString(jwt.getSubject());push.mute(actor,room,request.muted());events.publishEvent(new com.qlink.features.chat.realtime.RoomSettingsChanged(actor,room));return ApiResponse.ok("Notifications updated",null);}
}
