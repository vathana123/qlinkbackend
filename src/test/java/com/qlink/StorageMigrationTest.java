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
class StorageMigrationTest {
    @Container static final MySQLContainer mysql=new MySQLContainer("mysql:8.4").withDatabaseName("storage_upgrade");
    @Test void v5AddsStorageWithoutChangingExistingRowsAndEnforcesConstraints() {
        var source=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
        Flyway.configure().dataSource(source).target("4").load().migrate();
        var db=new JdbcTemplate(source); String owner=UUID.randomUUID().toString();
        db.update("insert into users(id,status,display_name,created_at,updated_at) values(UUID_TO_BIN(?),'ACTIVE','Preserved',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",owner);
        var before=db.queryForList("select BIN_TO_UUID(id),status,display_name,created_at,updated_at from users");
        var flyway=Flyway.configure().dataSource(source).target("5").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1); flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForList("select BIN_TO_UUID(id),status,display_name,created_at,updated_at from users")).isEqualTo(before);
        String key=UUID.randomUUID()+".blob";
        String insert="insert into stored_files(id,original_name,storage_key,content_type,size_bytes,checksum_sha256,category,status,uploaded_by,created_at,updated_at) values(UUID_TO_BIN(UUID()),'notes.txt',?,'text/plain',?,?,'DOCUMENT','AVAILABLE',UUID_TO_BIN(?),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))";
        db.update(insert,key,5,"a".repeat(64),owner);
        assertThatThrownBy(()->db.update(insert,key,5,"a".repeat(64),owner)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->db.update(insert,UUID.randomUUID()+".blob",5,"a".repeat(64),UUID.randomUUID().toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->db.update(insert,UUID.randomUUID()+".blob",0,"a".repeat(64),owner)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->db.update("update stored_files set status='DELETED'")).isInstanceOf(DataAccessException.class);
        assertThat(db.queryForObject("select count(*) from stored_files",Integer.class)).isEqualTo(1);
    }
}
