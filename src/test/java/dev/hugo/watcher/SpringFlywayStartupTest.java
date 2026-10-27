package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "watcher.runtime.scheduling-enabled=false")
class SpringFlywayStartupTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("watcher.telegram-bot-token", () -> "synthetic-test-token");
        registry.add("watcher.telegram-chat-id", () -> "123456789");
        registry.add("watcher.group-jids", () -> "120363000000000000@g.us");
        registry.add("watcher.evolution.webhook-token", () -> "startup-test-token");
        registry.add("watcher.evolution.instance", () -> "watcher-startup-test");
    }

    @Autowired JdbcTemplate jdbc;

    @Test void appliesFlywayMigrationsAutomaticallyDuringApplicationStartup() {
        assertEquals("11", jdbc.queryForObject(
                "select version from flyway_schema_history order by installed_rank desc limit 1", String.class));
        assertEquals("UNKNOWN", jdbc.queryForObject("select connection_state from watcher_connection_state where singleton_id=1", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from inbox_events", Integer.class));
    }
}
