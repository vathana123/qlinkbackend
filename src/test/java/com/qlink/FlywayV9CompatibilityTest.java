package com.qlink;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class FlywayV9CompatibilityTest {
 @Container static final MySQLContainer mysql=new MySQLContainer("mysql:8.4").withDatabaseName("v9_compatibility");
 @Test void originalV9UpgradesWithoutRepairOrChangingExistingRoles() {
  var source=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
  var original=Flyway.configure().dataSource(source).target("9").load();
  original.migrate(); original.validate();
  var db=new JdbcTemplate(source);
  assertThat(db.queryForObject("select checksum from flyway_schema_history where version='9'",Integer.class)).isEqualTo(-2128036722);
  String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
  for(String id:new String[]{a,b}) db.update("insert into users(id,status,created_at,updated_at) values(UUID_TO_BIN(?),'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",id);
  String missing=room(db,a),valid=room(db,a),fallback=room(db,a);
  member(db,missing,a,"MEMBER");member(db,missing,b,"CO_OWNER");
  member(db,valid,a,"CO_OWNER");member(db,valid,b,"OWNER");
  member(db,fallback,b,"MEMBER");
  var before=db.queryForList("select BIN_TO_UUID(user_id) user_id,role from chat_room_members where room_id=UUID_TO_BIN(?) order by user_id",valid);
  var current=Flyway.configure().dataSource(source).target("11").load();
  assertThat(current.migrate().migrationsExecuted).isEqualTo(2);
  current.validate();
  assertThat(db.queryForObject("select checksum from flyway_schema_history where version='9'",Integer.class)).isEqualTo(-2128036722);
  assertThat(db.queryForObject("select count(*) from chat_rooms",Integer.class)).isEqualTo(3);
  assertThat(db.queryForObject("select count(*) from chat_room_members",Integer.class)).isEqualTo(5);
  assertThat(role(db,missing,a)).isEqualTo("OWNER");
  assertThat(role(db,missing,b)).isEqualTo("CO_OWNER");
  assertThat(role(db,fallback,b)).isEqualTo("OWNER");
  assertThat(db.queryForList("select BIN_TO_UUID(user_id) user_id,role from chat_room_members where room_id=UUID_TO_BIN(?) order by user_id",valid)).isEqualTo(before);
  assertThatThrownBy(() -> db.update("update chat_room_members set role='OWNER' where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?)",valid,a)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThat(current.migrate().migrationsExecuted).isZero();
 }
 private String room(JdbcTemplate db,String creator) {
  String id=UUID.randomUUID().toString();
  db.update("insert into chat_rooms(id,type,creator_id,group_name,created_at,updated_at) values(UUID_TO_BIN(?),'GROUP_PRIVATE',UUID_TO_BIN(?),'Migration fixture',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",id,creator);
  return id;
 }
 private void member(JdbcTemplate db,String room,String user,String role) {
  db.update("insert into chat_room_members(id,room_id,user_id,role,joined_at,created_at,updated_at) values(UUID_TO_BIN(UUID()),UUID_TO_BIN(?),UUID_TO_BIN(?),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",room,user,role);
 }
 private String role(JdbcTemplate db,String room,String user) {
  return db.queryForObject("select role from chat_room_members where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?)",String.class,room,user);
 }
}
