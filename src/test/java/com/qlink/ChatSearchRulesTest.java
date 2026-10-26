package com.qlink;

import com.qlink.features.chat.service.ChatSearchService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ChatSearchRulesTest {
    @Test void literalSearchEscapesWildcardsAndRejectsUnboundedInput() {
        assertThat(ChatSearchService.pattern("  A_%!  ")).isEqualTo("%a!_!%!!%");
        assertThat(ChatSearchService.pattern("សួស្តី")).isEqualTo("%សួស្តី%");
        for (String term : new String[]{"", "a", "a".repeat(101), "a\nb"})
            assertThatThrownBy(() -> ChatSearchService.pattern(term)).isInstanceOf(com.qlink.common.exception.ApiException.class);
    }
    @Test void cursorPreservesMicrosecondsAndRejectsOtherScopesAndMalformedValues() {
        var cursor = new ChatSearchService.Cursor(Instant.parse("2026-01-01T00:00:00.123456Z"), UUID.randomUUID());
        var value = cursor.encode("messages");
        assertThat(ChatSearchService.Cursor.read(value, "messages")).isEqualTo(cursor);
        assertThatThrownBy(() -> ChatSearchService.Cursor.read(value, "rooms")).isInstanceOf(com.qlink.common.exception.ApiException.class);
        for (String invalid : new String[]{"not-a-cursor", "x".repeat(257)})
            assertThatThrownBy(() -> ChatSearchService.Cursor.read(invalid, "messages")).isInstanceOf(com.qlink.common.exception.ApiException.class);
    }
}
