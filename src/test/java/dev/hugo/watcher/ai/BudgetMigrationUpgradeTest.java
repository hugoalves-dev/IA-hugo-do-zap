package dev.hugo.watcher.ai;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class BudgetMigrationUpgradeTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void upgradesExistingV20BudgetRowsWithoutRepairOrChecksumChange() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway previousRelease = Flyway.configure().dataSource(source).target("20").load();
        previousRelease.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        UUID existing = UUID.randomUUID();
        LocalDate today = LocalDate.parse("2026-10-27");
        jdbc.update("""
                insert into ai_budget_reservations(reservation_id,estimated_micros,reserved_micros,
                    reservation_day,reservation_month,status,created_at,updated_at)
                values (?,10,8,?,'2026-10-01','RESERVED',now(),now())
                """, existing, today);
        Integer checksumBefore = jdbc.queryForObject("select checksum from flyway_schema_history where version='20'", Integer.class);

        Flyway currentRelease = Flyway.configure().dataSource(source).target("21").load();
        currentRelease.migrate();

        assertEquals("21", currentRelease.info().current().getVersion().getVersion());
        assertEquals(checksumBefore, jdbc.queryForObject(
                "select checksum from flyway_schema_history where version='20'", Integer.class));
        assertEquals(8L, jdbc.queryForObject(
                "select reserved_micros from ai_budget_reservations where reservation_id=?", Long.class, existing));

        UUID dispatched = UUID.randomUUID();
        jdbc.update("""
                insert into ai_budget_reservations(reservation_id,estimated_micros,reserved_micros,
                    reservation_day,reservation_month,status,created_at,updated_at)
                values (?,10,10,?,'2026-10-01','DISPATCHED',now(),now())
                """, dispatched, today);
        assertEquals(2, jdbc.queryForObject("select count(*) from ai_budget_reservations", Integer.class));
    }
}
