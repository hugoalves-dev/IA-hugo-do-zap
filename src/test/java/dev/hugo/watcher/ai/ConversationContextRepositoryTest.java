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

    @Test void directChatIncludesPriorOwnerMessageWithRole() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc = new JdbcTemplate(source);
        Instant now = Instant.parse("2026-10-30T12:00:00Z");
        String chat = "direct-context@s.whatsapp.net";
        jdbc.update("insert into simulation_owner_context(chat_jid,external_id,received_at,captured_at,expires_at,text) values (?,?,?,?,?,?)",
                chat, "owner", java.sql.Timestamp.from(now.minusSeconds(20)), java.sql.Timestamp.from(now.minusSeconds(20)), java.sql.Timestamp.from(now.plusSeconds(300)), "Posso apagar?");
        var target = new IncomingMessage(Optional.of("target"), chat, Optional.empty(), Optional.empty(), Optional.empty(), false,
                "notify", false, Map.of("conversation", "pode"), now);
        var batch = new Batch(java.util.UUID.randomUUID(), chat, "", List.of(target));
        var input = new ConversationContextRepository(jdbc, Duration.ofHours(24)).input(batch, now.plusSeconds(1));
        assertEquals("pode", input.target());
        assertEquals(List.of(new ClassificationInput.ContextEntry("owner", "Posso apagar?")), input.contextEntries());
    }

    @Test void groupOwnerContextRequiresReplyToSameParticipantAndKeepsChronologicalOrder() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc = new JdbcTemplate(source);
        var debounce = new DebounceService(jdbc, new WatcherSimulationProperties());
        Instant now = Instant.parse("2026-10-30T12:00:00Z");
        String chat = "team@g.us";
        add(debounce, "alice-prior", chat, "alice", "Você consegue revisar?", now.minusSeconds(120));
        add(debounce, "bob-prior", chat, "bob", "Preciso de ajuda", now.minusSeconds(115));
        debounce.claimReady(now.minusSeconds(90));
        insertOwner(jdbc, chat, "owner-alice", "alice-prior", "Vou revisar", now.minusSeconds(40), now);
        insertOwner(jdbc, chat, "owner-bob", "bob-prior", "Vou ajudar", now.minusSeconds(30), now);
        add(debounce, "alice-target", chat, "alice", "Pode fazer isso?", now.minusSeconds(10));
        Batch batch = debounce.claimReady(now.plusSeconds(1)).stream()
                .filter(candidate -> candidate.participantJid().equals("alice")).findFirst().orElseThrow();

        var input = new ConversationContextRepository(jdbc, Duration.ofHours(24)).input(batch, now.plusSeconds(2));

        assertEquals("Pode fazer isso?", input.target());
        assertEquals(List.of(new ClassificationInput.ContextEntry("contact", "Você consegue revisar?"),
                new ClassificationInput.ContextEntry("owner", "Vou revisar")), input.contextEntries());
    }

    @Test void privateAlternatingBurstSendsOnlyFiveMostRecentPriorMessages() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc = new JdbcTemplate(source);
        String chat = "burst@s.whatsapp.net";
        Instant now = Instant.parse("2026-10-30T12:00:00Z");
        for (int i = 0; i < 6; i++) {
            insertOwner(jdbc, chat, "owner-" + i, null, "owner-" + i, now.minusSeconds(60 - i * 10L), now);
            add(new DebounceService(jdbc, new WatcherSimulationProperties()), "contact-" + i, chat, "",
                    "contact-" + i, now.minusSeconds(55 - i * 10L));
        }
        var target = new IncomingMessage(Optional.of("burst-target"), chat, Optional.empty(), Optional.empty(), Optional.empty(),
                false, "notify", false, Map.of("conversation", "final target"), now);
        var batch = new Batch(java.util.UUID.randomUUID(), chat, "", List.of(target));

        var input = new ConversationContextRepository(jdbc, Duration.ofHours(24)).input(batch, now.plusSeconds(1));

        assertEquals(List.of("contact-3", "owner-4", "contact-4", "owner-5", "contact-5"), input.context());
        assertEquals("final target", input.target());
    }

    private static void insertOwner(JdbcTemplate jdbc, String chat, String id, String replyTo, String text,
                                    Instant at, Instant now) {
        jdbc.update("""
                insert into simulation_owner_context(chat_jid,external_id,received_at,captured_at,expires_at,text,reply_to_external_id)
                values (?,?,?,?,?,?,?)
                """, chat, id, java.sql.Timestamp.from(at), java.sql.Timestamp.from(at),
                java.sql.Timestamp.from(now.plusSeconds(300)), text, replyTo);
    }

    private static void add(DebounceService service, String id, String chat, String participant, String text, Instant at) {
        service.enqueue(new IncomingMessage(Optional.of(id), chat, Optional.of(participant), Optional.empty(), Optional.empty(), false,
                "notify", false, Map.of("conversation", text), at), at);
    }
}
