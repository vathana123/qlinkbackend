package com.qlink;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
@Testcontainers
class AttachmentMigrationTest {
    @Container static final MySQLContainer mysql=new MySQLContainer("mysql:8.4").withDatabaseName("attachment_upgrade");
    @Test void upgradesV6WithoutChangingTextAndRejectsMissingAttachment() {
        var source=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
        Flyway.configure().dataSource(source).target("6").load().migrate();
        var db=new JdbcTemplate(source); String owner=UUID.randomUUID().toString(), room=UUID.randomUUID().toString();
        db.update("insert into users(id,status,created_at,updated_at) values(UUID_TO_BIN(?),'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",owner);
        db.update("insert into chat_rooms(id,type,creator_id,personal_pair_key,created_at,updated_at) values(UUID_TO_BIN(?),'PERSONAL',UUID_TO_BIN(?),'pair',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",room,owner);
        db.update("insert into chat_messages(id,room_id,sender_id,client_message_id,type,content,created_at,updated_at) values(UUID_TO_BIN(UUID()),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(UUID()),'TEXT','Preserved',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",room,owner);
        var before=db.queryForList("select BIN_TO_UUID(id),content,created_at from chat_messages");
        var migration=Flyway.configure().dataSource(source).target("7").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1); migration.validate();
        assertThat(db.queryForList("select BIN_TO_UUID(id),content,created_at from chat_messages")).isEqualTo(before);
        assertThatThrownBy(() -> db.update("update chat_messages set type='IMAGE'" )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.update("update chat_messages set type='UNKNOWN'" )).isInstanceOf(DataAccessException.class);
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }
}
