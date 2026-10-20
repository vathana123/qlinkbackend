package com.qlink;
import com.qlink.features.user.service.DiscoveryService;
import com.qlink.features.chat.service.ChatService;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class ChatRulesTest {
 @Test void usernameRules() {
  assertThat(DiscoveryService.normalize("User_123")).isEqualTo("user_123");
  for(String value:new String[]{"admin","SUPPORT","system","ab","has space","éclair","a".repeat(31)})
   assertThatThrownBy(()->DiscoveryService.normalize(value)).isInstanceOf(com.qlink.common.exception.ApiException.class);
 }
 @Test void canonicalPairOrderUsesStableUuidText() {
  UUID a=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),b=UUID.fromString("00000000-0000-0000-0000-000000000001");
  assertThat(ChatService.pairKey(a,b)).isEqualTo(ChatService.pairKey(b,a)).isEqualTo(b+":"+a);
 }
}
