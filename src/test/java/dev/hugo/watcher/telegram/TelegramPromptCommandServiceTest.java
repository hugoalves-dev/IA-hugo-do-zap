package dev.hugo.watcher.telegram;

import dev.hugo.watcher.NotificationOutboxRepository;
import dev.hugo.watcher.ai.ClassificationPromptService;
import dev.hugo.watcher.ai.ClassificationPromptValidator;
import dev.hugo.watcher.ai.JdbcClassificationPromptRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class TelegramPromptCommandServiceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void existingSingleMessageSessionRemainsUsableAndBoundToPrivateChat() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        Clock clock = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC);
        var prompts = new ClassificationPromptService(new JdbcClassificationPromptRepository(jdbc, manager),
                new ClassificationPromptValidator(), manager);
        var tokens = new CallbackTokenService(new CallbackTokenLedgerRepository(jdbc), clock);
        var commands = new AdminCommandService(jdbc, manager, tokens, new NotificationOutboxRepository(jdbc, manager), clock);
        commands.configurePromptSettings(prompts);

        prompts.beginInput(1001, 2002, clock.instant().plusSeconds(600), clock.instant());
        assertNull(commands.acceptPromptInput("Wrong chat", 1001, 9999, null));
        assertTrue(commands.acceptPromptInput("Legacy operational guidance.", 1001, 2002, null).accepted());
        assertNull(commands.acceptPromptInput("Already consumed", 1001, 2002, null));
        assertEquals("Legacy operational guidance.", prompts.latest().getFirst().body());
        assertEquals(0, prompts.current(true).revision());
    }
}
