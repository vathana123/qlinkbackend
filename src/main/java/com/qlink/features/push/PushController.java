package com.qlink.features.push;
import com.qlink.common.response.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
@RestController @RequestMapping("/api/v1")
public class PushController {
 private final PushService push;public PushController(PushService push){this.push=push;}
 public record Registration(@NotBlank @Size(max=4096) String token,@NotBlank String platform){}
 public record Mute(boolean muted){}
 @PutMapping("/push/devices/{installation}") public ApiResponse<Void> register(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID installation,@Valid @RequestBody Registration request){push.register(UUID.fromString(jwt.getSubject()),UUID.fromString(jwt.getClaimAsString("sid")),installation,request.token(),request.platform());return ApiResponse.ok("Device registered",null);}
 @DeleteMapping("/push/devices/{installation}") public ApiResponse<Void> unregister(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID installation){push.unregister(UUID.fromString(jwt.getSubject()),UUID.fromString(jwt.getClaimAsString("sid")),installation);return ApiResponse.ok("Device removed",null);}
 @GetMapping("/chat/rooms/{room}/notifications") public ApiResponse<Mute> preference(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID room){return ApiResponse.ok("Notifications",new Mute(push.muted(UUID.fromString(jwt.getSubject()),room)));}
 @PutMapping("/chat/rooms/{room}/notifications") public ApiResponse<Void> mute(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID room,@RequestBody Mute request){push.mute(UUID.fromString(jwt.getSubject()),room,request.muted());return ApiResponse.ok("Notifications updated",null);}
}
