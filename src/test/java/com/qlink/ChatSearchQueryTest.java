package com.qlink;

import com.qlink.features.chat.entity.*;
import com.qlink.features.chat.service.*;
import com.qlink.features.user.entity.User;
import com.qlink.features.user.service.UserService;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Compiles real HQL into MySQL SQL without requiring Docker. Behavioral access
 * tests against MySQL live in ChatIntegrationTest.search*. */
class ChatSearchQueryTest {
    private static class QueryCompiled extends RuntimeException {}
    @Test void mysqlQueriesCompileWithRestrictionsAndDatabaseLimits() throws Exception {
        var connection = mock(Connection.class);
        var provider = mock(ConnectionProvider.class);
        when(provider.getConnection()).thenReturn(connection);
        var sql = new ArrayList<String>();
        var registry = new StandardServiceRegistryBuilder()
            .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
            .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
            .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
            .applySetting("hibernate.hbm2ddl.auto", "none")
            .applySetting("hibernate.session_factory.statement_inspector", (StatementInspector) value -> { sql.add(value); throw new QueryCompiled(); })
            .addService(ConnectionProvider.class, provider).build();
        try (var factory = new MetadataSources(registry)
                .addAnnotatedClass(ChatMessage.class).addAnnotatedClass(ChatRoom.class)
                .addAnnotatedClass(ChatRoomMember.class).addAnnotatedClass(MessageHidden.class)
                .addAnnotatedClass(User.class).buildMetadata().buildSessionFactory();
             var em = factory.createEntityManager()) {
            var chat = mock(ChatService.class);
            var service = new ChatSearchService(em, chat, mock(UserService.class));
            UUID viewer = UUID.randomUUID(), room = UUID.randomUUID();
            String cursor = new ChatSearchService.Cursor(Instant.parse("2026-01-02T00:00:00Z"), UUID.randomUUID()).encode("messages");
            assertThatThrownBy(() -> service.rooms(viewer, "needle", null, 2)).isInstanceOf(QueryCompiled.class);
            assertThatThrownBy(() -> service.messages(viewer, null, "needle", viewer, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-03T00:00:00Z"), cursor, 2)).isInstanceOf(QueryCompiled.class);
            assertThatThrownBy(() -> service.messages(viewer, room, "needle", null, null, null, null, 2)).isInstanceOf(QueryCompiled.class);
            String newer = new ChatSearchService.Cursor(Instant.now(), UUID.randomUUID()).encode("newer:" + room);
            assertThatThrownBy(() -> service.newer(viewer, room, newer, 2)).isInstanceOf(QueryCompiled.class);
            assertThat(sql).hasSize(4).allSatisfy(query -> assertThat(query).contains("limit ?"));
            assertThat(sql.get(0)).contains("chat_room_members", "deleted_at IS NULL", "users", "escape '!'");
            for (String query : sql.subList(1, sql.size())) {
                assertThat(query).contains("chat_rooms", "chat_room_members", "message_hidden", "joined_at", "deleted_at IS NULL");
            }
            assertThat(sql.get(1)).contains("removed_at is null", "sender_id", "escape '!'");
        } finally { StandardServiceRegistryBuilder.destroy(registry); }
    }
}
