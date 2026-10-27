package dev.hugo.watcher;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class ArchiveModeServiceTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;

    @BeforeEach void migrateAndReset() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        manager = new DataSourceTransactionManager(dataSource);
        jdbc.update("update archive_monitor_settings set mode='ALL' where singleton_id=1");
    }

    @Test void defaultsToAllAndPersistsModeAcrossServiceInstances() {
        ArchiveModeService modes = new ArchiveModeService(jdbc, manager);

        assertEquals(ArchiveMode.ALL, modes.getMode());
        modes.setMode(ArchiveMode.UNARCHIVED_ONLY);

        assertEquals(ArchiveMode.UNARCHIVED_ONLY, new ArchiveModeService(jdbc, manager).getMode());
    }

    @Test void rejectsNullModeWithoutChangingPersistedMode() {
        ArchiveModeService modes = new ArchiveModeService(jdbc, manager);
        modes.setMode(ArchiveMode.ARCHIVED_ONLY);

        assertThrows(NullPointerException.class, () -> modes.setMode(null));
        assertEquals(ArchiveMode.ARCHIVED_ONLY, modes.getMode());
    }
}
