package dev.hugo.watcher.ai;

import dev.hugo.watcher.IncomingMessage;
import dev.hugo.watcher.simulation.Batch;
import dev.hugo.watcher.simulation.DebounceService;
import dev.hugo.watcher.simulation.WatcherSimulationProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ConversationContextRepositoryTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void retrievesOnlyUnexpiredPriorMessagesFromSameChatAndParticipant() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc = new JdbcTemplate(source);
        var debounce = new DebounceService(jdbc, new WatcherSimulationProperties());
        Instant now = Instant.parse("2026-10-30T10:00:00Z");
        add(debounce, "old", "group@g.us", "alice", "expirou", now.minus(Duration.ofHours(25)));
        add(debounce, "other", "group@g.us", "bob", "outro", now.minusSeconds(100));
        add(debounce, "prior", "group@g.us", "alice", "apaga", now.minusSeconds(60));
        debounce.claimReady(now.minusSeconds(30));
        add(debounce, "target", "group@g.us", "alice", "pode", now);
        Batch batch = debounce.claimReady(now.plusSeconds(11)).stream().filter(b -> b.participantJid().equals("alice")).findFirst().orElseThrow();
        var input = new ConversationContextRepository(jdbc, Duration.ofHours(24)).input(batch, now.plusSeconds(11));
        assertEquals("pode", input.target());
        assertEquals(List.of("apaga"), input.context());
        var noText = new IncomingMessage(Optional.of("empty"), "group@g.us", Optional.of("alice"), Optional.empty(), Optional.empty(), false,
                "notify", false, Map.of("audioMessage", Map.of()), now.plusSeconds(20));
        var emptyBatch = new Batch(java.util.UUID.randomUUID(), "group@g.us", "alice", List.of(noText));
        assertEquals("", new ConversationContextRepository(jdbc, Duration.ofHours(24)).input(emptyBatch, now.plusSeconds(21)).target());
    }

    private static void add(DebounceService service, String id, String chat, String participant, String text, Instant at) {
        service.enqueue(new IncomingMessage(Optional.of(id), chat, Optional.of(participant), Optional.empty(), Optional.empty(), false,
                "notify", false, Map.of("conversation", text), at), at);
    }
}
