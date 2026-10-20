package com.qlink;
import com.qlink.features.user.service.SearchKeyword;
import com.qlink.features.user.entity.User;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SearchKeywordTest {
 @Test void normalizedContactsAndEscapedPublicPatterns() {
  var email=SearchKeyword.parse("  EXAMPLE@DOMAIN.COM  ");
  assertThat(email.email()).isEqualTo("example@domain.com");
  assertThat(SearchKeyword.parse("domain.com").email()).isNull();
  assertThat(SearchKeyword.parse("_a%!b").pattern()).isEqualTo("%!_a!%!!b%");
  assertThat(SearchKeyword.parse("123").pattern()).isEqualTo("%123%");
  assertThat(SearchKeyword.normalizePhone("00 855 (12) 345-678")).isEqualTo("+85512345678");
  assertThat(SearchKeyword.normalizePhone("012345678")).isNull();
  assertThat(SearchKeyword.normalizePhone("+012345678")).isNull();
  assertThat(SearchKeyword.normalizePhone("+1234567890123456")).isNull();
 }
 @Test void phoneStorageIsNormalizedOptionalAndNeverAnAuthenticationField() {
  var user=new User();assertThat(user.getPhoneNumber()).isNull();
  user.setPhoneNumber("+855 12 345 678");assertThat(user.getPhoneNumber()).isEqualTo("+85512345678");
  assertThatThrownBy(()->user.setPhoneNumber("012345678")).isInstanceOf(com.qlink.common.exception.ApiException.class);
  user.setPhoneNumber(null);assertThat(user.getPhoneNumber()).isNull();
 }
}
