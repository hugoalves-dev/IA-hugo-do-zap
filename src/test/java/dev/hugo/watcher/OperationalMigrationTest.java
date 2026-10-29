package dev.hugo.watcher;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class OperationalMigrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17-alpine");
    @Test void preservesPriorAuditedEmergencyWithoutImportingLegacySettings() {
        var source=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(source).target("29").load().migrate();
        var jdbc=new JdbcTemplate(source);
        jdbc.update("update automation_settings set kill_switch_enabled=false");
        jdbc.update("update telegram_admin_settings set simulation_enabled=false");
        jdbc.update("insert into telegram_admin_audit(action,outcome,actor_user_id,actor_chat_id,created_at) values ('emergencia','APPLIED',1,1,now())");
        Flyway.configure().dataSource(source).load().migrate();
        assertTrue(jdbc.queryForObject("select emergency_latched from operational_restrictions",Boolean.class));
        assertFalse(jdbc.queryForObject("select simulation_paused from operational_restrictions",Boolean.class));
    }
}
