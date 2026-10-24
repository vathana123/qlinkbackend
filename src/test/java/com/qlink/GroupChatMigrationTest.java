package com.qlink;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class GroupChatMigrationTest {
    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.4").withDatabaseName("group_upgrade_test");

    @Test void upgradesPopulatedV3WithoutLosingPersonalHistoryOrConstraints() {
        var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(source).target("3").load().migrate();
        var db = new JdbcTemplate(source);
        String a=UUID.randomUUID().toString(), b=UUID.randomUUID().toString();
        for (String id : new String[]{a,b}) db.update("""
            insert into users (id,status,created_at,updated_at) values (UUID_TO_BIN(?),'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """,id);
        String active=UUID.randomUUID().toString(), historical=UUID.randomUUID().toString();
        String pair=a.compareTo(b)<0?a+":"+b:b+":"+a;
        for(String room:new String[]{historical,active}) {
            db.update("""
                insert into chat_rooms (id,type,creator_id,personal_pair_key,created_at,updated_at,created_by,deleted_at)
                values (UUID_TO_BIN(?),'PERSONAL',UUID_TO_BIN(?),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UUID_TO_BIN(?),
                    IF(?=1,UTC_TIMESTAMP(6),NULL))
                """,room,a,pair,a,room.equals(historical)?1:0);
            for(String user:new String[]{a,b}) db.update("""
                insert into chat_room_members (id,room_id,user_id,joined_at,created_at,updated_at)
                values (UUID_TO_BIN(UUID()),UUID_TO_BIN(?),UUID_TO_BIN(?),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """,room,user);
        }
        db.update("""
            insert into chat_messages (id,room_id,sender_id,client_message_id,type,content,created_at,updated_at)
            values (UUID_TO_BIN(UUID()),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(UUID()),'TEXT','Preserved text',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """,active,a);
        String columns="BIN_TO_UUID(id) id,type,BIN_TO_UUID(creator_id) creator,personal_pair_key,created_at,updated_at,deleted_at,BIN_TO_UUID(created_by) actor";
        var before=db.queryForList("select "+columns+" from chat_rooms order by id");
        var flyway=Flyway.configure().dataSource(source).target("4").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForList("select "+columns+" from chat_rooms order by id")).isEqualTo(before);
        assertThat(db.queryForObject("select count(*) from chat_rooms where message_history_visibility='VIEW_ALL_MESSAGES' and group_name is null",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from chat_room_members where role='MEMBER'",Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("select content from chat_messages",String.class)).isEqualTo("Preserved text");
        assertThatThrownBy(()->db.update("""
            insert into chat_rooms (id,type,creator_id,personal_pair_key,created_at,updated_at)
            values (UUID_TO_BIN(UUID()),'PERSONAL',UUID_TO_BIN(?),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """,a,pair)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update("""
            insert into chat_room_members (id,room_id,user_id,joined_at,created_at,updated_at)
            values (UUID_TO_BIN(UUID()),UUID_TO_BIN(?),UUID_TO_BIN(?),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """,active,a)).isInstanceOf(DataIntegrityViolationException.class);
        // Both group types can coexist with NULL pair keys and preserve an explicit history policy.
        for(String type:new String[]{"GROUP_PRIVATE","GROUP_PUBLIC"}) db.update("""
            insert into chat_rooms (id,type,creator_id,group_name,message_history_visibility,created_at,updated_at)
            values (UUID_TO_BIN(UUID()),?,UUID_TO_BIN(?),'Test group','VIEW_AFTER_JOIN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """,type,a);
        assertThat(db.queryForObject("select count(*) from chat_rooms where type <> 'PERSONAL' and active_pair is null and personal_pair_key is null",Integer.class)).isEqualTo(2);
        assertThatThrownBy(()->db.update("update chat_rooms set personal_pair_key=null where id=UUID_TO_BIN(?)",active)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("ck_chat_room_pair");
        assertThatThrownBy(()->db.update("update chat_rooms set personal_pair_key=? where type='GROUP_PRIVATE'",pair)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("ck_chat_room_pair");
        assertThatThrownBy(()->db.update("update chat_rooms set type='UNSUPPORTED' where id=UUID_TO_BIN(?)",active)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("Check constraint");
        assertThatThrownBy(()->db.update("update chat_rooms set message_history_visibility='INVALID' where id=UUID_TO_BIN(?)",active)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("ck_message_history_visibility");
        assertThatThrownBy(()->db.update("update chat_room_members set role='ADMIN' where room_id=UUID_TO_BIN(?)",active)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("ck_chat_room_member_role");
        db.update("update chat_room_members set role='OWNER' where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?)",active,a);
        assertThat(db.queryForObject("select count(*) from chat_room_members where role='OWNER'",Integer.class)).isEqualTo(1);
        String legacyGroup=UUID.randomUUID().toString();
        db.update("""
            insert into chat_rooms (id,type,creator_id,group_name,message_history_visibility,created_at,updated_at)
            values (UUID_TO_BIN(?),'GROUP_PRIVATE',UUID_TO_BIN(?),'Legacy group','VIEW_AFTER_JOIN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """,legacyGroup,a);
        for(String user:new String[]{a,b}) db.update("""
            insert into chat_room_members (id,room_id,user_id,joined_at,created_at,updated_at)
            values (UUID_TO_BIN(UUID()),UUID_TO_BIN(?),UUID_TO_BIN(?),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """,legacyGroup,user);
        Flyway.configure().dataSource(source).load().migrate();
        assertThat(db.queryForObject("select count(*) from chat_room_members where room_id=UUID_TO_BIN(?) and role='OWNER' and deleted_at is null",Integer.class,legacyGroup)).isEqualTo(1);
        assertThat(db.queryForObject("select BIN_TO_UUID(user_id) from chat_room_members where room_id=UUID_TO_BIN(?) and role='OWNER' and deleted_at is null",String.class,legacyGroup)).isEqualTo(a);
        // Old inserts remain valid, and soft deletion still releases the personal pair.
        db.update("update chat_rooms set deleted_at=UTC_TIMESTAMP(6) where id=UUID_TO_BIN(?)",active);
        db.update("""
            insert into chat_rooms (id,type,creator_id,personal_pair_key,created_at,updated_at)
            values (UUID_TO_BIN(UUID()),'PERSONAL',UUID_TO_BIN(?),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """,a,pair);
        assertThat(db.queryForObject("select count(*) from chat_rooms where active_pair=?",Integer.class,pair)).isEqualTo(1);
    }
}
