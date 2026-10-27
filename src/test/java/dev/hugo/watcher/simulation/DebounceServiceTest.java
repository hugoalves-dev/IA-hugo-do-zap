package dev.hugo.watcher.simulation;

import dev.hugo.watcher.IncomingMessage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class DebounceServiceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private WatcherSimulationProperties limits;

    @BeforeEach void migrateAndReset() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).load().migrate();
        jdbc.execute("drop trigger if exists pause_simulation_batch_insert on simulation_batches");
        jdbc.execute("drop function if exists pause_simulation_batch_insert()");
        jdbc.update("delete from simulation_decisions");
        jdbc.update("delete from simulation_batch_messages");
        jdbc.update("delete from simulation_batches");
        limits = new WatcherSimulationProperties();
    }

    @Test void claimsAfterTenSecondsOfInactivityAndSurvivesServiceRestart() {
        var first = at("m1", "chat@g.us", "alice", "2026-10-27T10:00:00Z");
        new DebounceService(jdbc, limits).enqueue(first, first.receivedAt());
        assertTrue(new DebounceService(jdbc, limits).claimReady(first.receivedAt().plusSeconds(9)).isEmpty());
        var recovered = new DebounceService(jdbc, limits).claimReady(first.receivedAt().plusSeconds(10));
        assertEquals(1, recovered.size());
        assertEquals(List.of("m1"), recovered.getFirst().messages().stream().map(m -> m.externalId().orElseThrow()).toList());
    }

    @Test void capsWaitAtThirtySecondsAndSeparatesGroupParticipants() {
        var start = Instant.parse("2026-10-27T10:00:00Z");
        var service = new DebounceService(jdbc, limits);
        service.enqueue(at("m1", "chat@g.us", "alice", start.toString()), start);
        service.enqueue(at("m2", "chat@g.us", "alice", start.plusSeconds(25).toString()), start.plusSeconds(25));
        service.enqueue(at("m3", "chat@g.us", "bob", start.plusSeconds(25).toString()), start.plusSeconds(25));
        assertTrue(service.claimReady(start.plusSeconds(29)).isEmpty());
        var ready = service.claimReady(start.plusSeconds(30));
        assertEquals(1, ready.size());
        assertEquals("alice", ready.getFirst().participantJid());
        assertEquals("bob", service.claimReady(start.plusSeconds(35)).getFirst().participantJid());
    }

    @Test void deduplicatesMessageIdsAndEnforcesConfiguredBatchLimit() {
        limits.setMaxMessages(2);
        var service = new DebounceService(jdbc, limits);
        var start = Instant.parse("2026-10-27T10:00:00Z");
        service.enqueue(at("same", "chat@s.whatsapp.net", null, start.toString()), start);
        service.enqueue(at("same", "chat@s.whatsapp.net", null, start.plusSeconds(1).toString()), start.plusSeconds(1));
        service.enqueue(at("next", "chat@s.whatsapp.net", null, start.plusSeconds(2).toString()), start.plusSeconds(2));
        service.enqueue(at("third", "chat@s.whatsapp.net", null, start.plusSeconds(3).toString()), start.plusSeconds(3));
        assertEquals(3, jdbc.queryForObject("select count(*) from simulation_batch_messages", Integer.class));
        assertEquals(1, service.claimReady(start.plusSeconds(2)).size());
    }

    @Test void concurrentEnqueueForSameChatAndParticipantJoinsOneOpenBatch() throws Exception {
        pauseBatchInserts();
        Instant now = Instant.parse("2026-10-27T10:00:00Z");
        var service = new DebounceService(jdbc, limits);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Future<Throwable> first = pool.submit(() -> enqueueAfter(start, service, at("parallel-1", "group@g.us", "alice", now.toString()), now));
            Future<Throwable> second = pool.submit(() -> enqueueAfter(start, service, at("parallel-2", "group@g.us", "alice", now.toString()), now));
            start.countDown();
            assertNull(first.get());
            assertNull(second.get());
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from simulation_batches where status='OPEN'", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from simulation_batch_messages", Integer.class));
    }

    @Test void concurrentDifferentChatsCannotExceedGlobalPendingCapacity() throws Exception {
        limits.setMaxPendingBatches(1);
        pauseBatchInserts();
        Instant now = Instant.parse("2026-10-27T10:00:00Z");
        var service = new DebounceService(jdbc, limits);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Future<Throwable> first = pool.submit(() -> enqueueAfter(start, service, at("capacity-1", "one@s.whatsapp.net", null, now.toString()), now));
            Future<Throwable> second = pool.submit(() -> enqueueAfter(start, service, at("capacity-2", "two@s.whatsapp.net", null, now.toString()), now));
            start.countDown();
            Throwable firstResult = first.get();
            Throwable secondResult = second.get();
            int successes = (firstResult == null ? 1 : 0) + (secondResult == null ? 1 : 0);
            assertEquals(1, successes);
            Throwable rejected = firstResult == null ? secondResult : firstResult;
            assertInstanceOf(IllegalStateException.class, rejected);
            assertEquals("simulation batch capacity reached", rejected.getMessage());
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from simulation_batches where status in ('OPEN','READY','CLAIMED')", Integer.class));
    }

    private void pauseBatchInserts() {
        jdbc.execute("""
                create function pause_simulation_batch_insert() returns trigger language plpgsql as $$
                begin perform pg_sleep(0.4); return new; end $$
                """);
        jdbc.execute("create trigger pause_simulation_batch_insert before insert on simulation_batches for each row execute function pause_simulation_batch_insert()");
    }

    private static Throwable enqueueAfter(CountDownLatch start, DebounceService service, IncomingMessage message, Instant now) {
        try {
            start.await();
            service.enqueue(message, now);
            return null;
        } catch (Exception error) { return error; }
    }

    private static IncomingMessage at(String id, String chat, String participant, String at) {
        return new IncomingMessage(Optional.of(id), chat, Optional.ofNullable(participant), Optional.empty(), Optional.empty(),
                false, "notify", false, Map.of("conversation", id), Instant.parse(at));
    }
}
