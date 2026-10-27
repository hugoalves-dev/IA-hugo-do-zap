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

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ConnectionServiceTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private ConnectionService connections;

    @BeforeEach void migrateAndReset() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc.update("delete from outbox_actions");
        jdbc.update("delete from watcher_connection_state");
        var manager = new DataSourceTransactionManager(dataSource);
        var properties = new WatcherProperties();
        properties.setN8nSecret("synthetic-test-secret");
        connections = new ConnectionService(jdbc, manager, new NotificationOutboxRepository(jdbc, manager), properties, Duration.ofSeconds(60));
    }

    @Test void aBriefDisconnectThatRecoversDuringGraceDoesNotQueueAnAlert() {
        Instant openedAt = Instant.parse("2026-10-26T10:00:00Z");
        connections.onState(new ConnectionEvent(ConnectionEvent.State.CONNECTED, openedAt, "open", null));
        connections.onState(new ConnectionEvent(ConnectionEvent.State.DISCONNECTED, openedAt.plusSeconds(1), "connection_lost", 503));
        connections.onState(new ConnectionEvent(ConnectionEvent.State.CONNECTED, openedAt.plusSeconds(20), "open", null));
        connections.flushGracePeriod(openedAt.plusSeconds(120));

        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_actions where action_type='whatsapp_connection_lost'", Integer.class));
    }

    @Test void deviceRemovedQueuesOneImmediatePairingAlertPerOutage() {
        Instant openedAt = Instant.parse("2026-10-26T10:00:00Z");
        var removed = new ConnectionEvent(ConnectionEvent.State.DEVICE_REMOVED, openedAt, "device_removed", 401);

        connections.onState(removed);
        connections.onState(removed);

        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_actions where action_type='whatsapp_connection_lost'", Integer.class));
        assertEquals("true", jdbc.queryForObject("select payload->>'manualPairingRequired' from outbox_actions where action_type='whatsapp_connection_lost'", String.class));
    }
}
