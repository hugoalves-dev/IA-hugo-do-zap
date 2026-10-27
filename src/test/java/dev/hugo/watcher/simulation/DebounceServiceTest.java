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
        jdbc.update("delete from processed_message");
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

    @Test void oldCaptureKeepsOriginalDeadlineThroughTransferAndPurge() {
        Instant now = Instant.parse("2026-10-27T10:00:00Z");
        Instant originalDeadline = Instant.parse("2026-10-28T10:00:00Z");
        capture(at("old-capture", "old@s.whatsapp.net", null, "2026-10-21T10:00:00Z"),
                Instant.parse("2026-10-21T10:00:00Z"), originalDeadline);
        new DebounceService(jdbc, limits).claimReady(now);
        assertEquals(originalDeadline, jdbc.queryForObject("select expires_at from simulation_batches", java.sql.Timestamp.class).toInstant());
        var retention = new dev.hugo.watcher.RetentionService(jdbc,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        retention.purge(originalDeadline.minusSeconds(1));
        assertEquals(1,jdbc.queryForObject("select count(*) from simulation_batch_messages", Integer.class));
        retention.purge(originalDeadline);
        assertEquals(0,jdbc.queryForObject("select count(*) from simulation_batch_messages", Integer.class));
    }

    @Test void mixedDeadlinesUseEarliestBatchDeadlineWithoutExtendingEitherItem() {
        Instant now = Instant.parse("2026-10-27T10:00:00Z");
        Instant earliest = Instant.parse("2026-10-27T10:00:30Z");
        capture(at("short", "mixed@s.whatsapp.net", null, "2026-10-21T10:00:00Z"),
                Instant.parse("2026-10-21T10:00:00Z"),earliest);
        capture(at("long", "mixed@s.whatsapp.net", null, "2026-10-21T10:00:05Z"),
                Instant.parse("2026-10-21T10:00:05Z"),Instant.parse("2026-10-28T10:00:05Z"));
        new DebounceService(jdbc, limits).claimReady(now);
        assertEquals(1,jdbc.queryForObject("select count(*) from simulation_batches",Integer.class));
        assertEquals(2,jdbc.queryForObject("select count(*) from simulation_batch_messages",Integer.class));
        assertEquals(earliest,jdbc.queryForObject("select expires_at from simulation_batches",java.sql.Timestamp.class).toInstant());
        new dev.hugo.watcher.RetentionService(jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())).purge(earliest);
        assertEquals(0,jdbc.queryForObject("select count(*) from simulation_batch_messages",Integer.class));
    }

    @Test void backlogPreservesReceiveTimeInactivityAndMaximumWindow() {
        Instant first = Instant.parse("2026-10-27T10:00:00Z");
        // Ingestion order differs from event time. Poll time must not collapse the original windows.
        for (int offset : List.of(31,0,9,18,27,42)) {
            Instant received = first.plusSeconds(offset);
            capture(at("backlog-"+offset,"backlog@s.whatsapp.net",null,received.toString()),
                    first.plusSeconds(60),Instant.parse("2026-11-03T10:01:00Z"));
        }
        List<Batch> batches = new DebounceService(jdbc,limits).claimReady(Instant.parse("2026-10-27T11:00:00Z"));
        assertEquals(3,batches.size());
        assertEquals(List.of("backlog-0","backlog-9","backlog-18","backlog-27"),
                batches.get(0).messages().stream().map(m -> m.externalId().orElseThrow()).toList());
        assertEquals("backlog-31",batches.get(1).messages().getFirst().externalId().orElseThrow());
        assertEquals("backlog-42",batches.get(2).messages().getFirst().externalId().orElseThrow());
        assertEquals(first,jdbc.queryForObject("select min(first_message_at) from simulation_batches",java.sql.Timestamp.class).toInstant());
    }

    @Test void duplicateCaptureCannotLeaveLaterDeadlineOnExistingPayload() {
        Instant now = Instant.parse("2026-10-27T10:00:00Z");
        IncomingMessage message = at("duplicate-deadline","duplicate@s.whatsapp.net",null,"2026-10-21T10:00:00Z");
        var service = new DebounceService(jdbc,limits);
        service.enqueue(message,now);
        Instant deadline = Instant.parse("2026-10-28T10:00:00Z");
        capture(message,Instant.parse("2026-10-21T10:00:00Z"),deadline);
        service.claimReady(now);
        assertEquals(deadline,jdbc.queryForObject("select expires_at from simulation_batches",java.sql.Timestamp.class).toInstant());
        assertEquals(1,jdbc.queryForObject("select count(*) from simulation_batch_messages",Integer.class));
    }

    @Test void lateMessageAcrossPollsKeepsFirstLastAndOriginalWindow() {
        Instant firstPoll = Instant.parse("2026-10-27T10:00:10Z");
        Instant deadline = Instant.parse("2026-10-28T10:00:00Z");
        var service = new DebounceService(jdbc,limits);
        capture(at("newer","late@s.whatsapp.net",null,"2026-10-27T10:00:10Z"),firstPoll,deadline);
        assertTrue(service.claimReady(firstPoll).isEmpty());
        capture(at("late","late@s.whatsapp.net",null,"2026-10-27T10:00:05Z"),firstPoll.plusSeconds(1),deadline.plusSeconds(60));
        assertTrue(service.claimReady(firstPoll.plusSeconds(2)).isEmpty());
        assertEquals(2,jdbc.queryForObject("select count(*) from simulation_batch_messages",Integer.class));
        assertEquals(Instant.parse("2026-10-27T10:00:05Z"),jdbc.queryForObject("select first_message_at from simulation_batches",java.sql.Timestamp.class).toInstant());
        assertEquals(firstPoll,jdbc.queryForObject("select last_message_at from simulation_batches",java.sql.Timestamp.class).toInstant());
        assertEquals(deadline,jdbc.queryForObject("select expires_at from simulation_batches",java.sql.Timestamp.class).toInstant());
        assertTrue(service.claimReady(Instant.parse("2026-10-27T10:00:19Z")).isEmpty());
        var ready = service.claimReady(Instant.parse("2026-10-27T10:00:20Z"));
        assertEquals(List.of("late","newer"),ready.getFirst().messages().stream().map(m -> m.externalId().orElseThrow()).toList());
    }

    @Test void lateMessageOutsideOriginalWindowDoesNotCloseNewerOpenBatch() {
        Instant firstPoll = Instant.parse("2026-10-27T10:00:10Z");
        Instant deadline = Instant.parse("2026-10-28T10:00:00Z");
        var service = new DebounceService(jdbc,limits);
        capture(at("recent","isolated@s.whatsapp.net",null,"2026-10-27T10:00:10Z"),firstPoll,deadline);
        service.claimReady(firstPoll);
        capture(at("old-window","isolated@s.whatsapp.net",null,"2026-10-27T09:59:35Z"),firstPoll.plusSeconds(1),deadline);
        var late = service.claimReady(firstPoll.plusSeconds(2));
        assertEquals(1,late.size());
        assertEquals("old-window",late.getFirst().messages().getFirst().externalId().orElseThrow());
        assertEquals(1,jdbc.queryForObject("select count(*) from simulation_batches where status='OPEN'",Integer.class));
        assertTrue(service.claimReady(firstPoll.plusSeconds(9)).isEmpty());
        assertEquals("recent",service.claimReady(firstPoll.plusSeconds(10)).getFirst().messages().getFirst().externalId().orElseThrow());
    }

    @Test void lateMessageCannotExpandExistingBatchBeyondThirtySeconds() {
        Instant first = Instant.parse("2026-10-27T10:00:10Z");
        var service = new DebounceService(jdbc,limits);
        for (int offset : List.of(0,9,18,25)) {
            service.enqueue(at("window-"+offset,"maximum@s.whatsapp.net",null,first.plusSeconds(offset).toString()),first.plusSeconds(offset));
        }
        capture(at("too-early","maximum@s.whatsapp.net",null,"2026-10-27T10:00:05Z"),first.plusSeconds(26),Instant.parse("2026-10-28T10:00:00Z"));
        var late = service.claimReady(first.plusSeconds(26));
        assertEquals(1,late.size());
        assertEquals("too-early",late.getFirst().messages().getFirst().externalId().orElseThrow());
        assertEquals(1,jdbc.queryForObject("select count(*) from simulation_batches where status='OPEN' and first_message_at='2026-10-27T10:00:10Z' and last_message_at='2026-10-27T10:00:35Z' and due_at='2026-10-27T10:00:40Z'",Integer.class));
    }

    @Test void malformedCaptureDoesNotBlockOtherChatsOrExtendItsDeadline() {
        Instant now = Instant.parse("2026-10-27T10:00:10Z");
        Instant deadline = Instant.parse("2026-10-28T10:00:00Z");
        capture(at("malformed","bad@s.whatsapp.net",null,"2026-10-27T10:00:00Z"),now.minusSeconds(10),deadline);
        jdbc.update("update processed_message set shadow_payload=jsonb_set(shadow_payload,'{receivedAt}',to_jsonb(?::text)) where message_id=?","not-an-instant","malformed");
        capture(at("healthy","healthy@s.whatsapp.net",null,"2026-10-27T10:00:01Z"),now,deadline);
        var ready = new DebounceService(jdbc,limits).claimReady(now.plusSeconds(2));
        assertEquals(1,ready.size());
        assertEquals("healthy@s.whatsapp.net",ready.getFirst().chatJid());
        assertEquals(1,jdbc.queryForObject("select count(*) from processed_message where message_id='malformed' and shadow_payload is not null and shadow_expires_at='2026-10-28T10:00:00Z'",Integer.class));
        capture(at("healthy-again","another@s.whatsapp.net",null,"2026-10-27T10:00:02Z"),now.plusSeconds(3),deadline);
        assertEquals("another@s.whatsapp.net",new DebounceService(jdbc,limits).claimReady(now.plusSeconds(4)).getFirst().chatJid());
    }

    @Test void capacityRejectsSeparateLateBatchButAllowsExistingChatToProgress() {
        limits.setMaxPendingBatches(1);
        Instant now = Instant.parse("2026-10-27T10:00:10Z");
        Instant deadline = Instant.parse("2026-10-28T10:00:00Z");
        var service = new DebounceService(jdbc,limits);
        capture(at("capacity-new","busy@s.whatsapp.net",null,"2026-10-27T10:00:10Z"),now,deadline);
        service.claimReady(now);
        capture(at("capacity-old","busy@s.whatsapp.net",null,"2026-10-27T09:59:35Z"),now.plusSeconds(1),deadline);
        capture(at("capacity-next","busy@s.whatsapp.net",null,"2026-10-27T10:00:12Z"),now.plusSeconds(2),deadline);
        service.claimReady(now.plusSeconds(2));
        assertEquals(1,jdbc.queryForObject("select count(*) from simulation_batches",Integer.class));
        assertEquals(2,jdbc.queryForObject("select count(*) from simulation_batch_messages",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from processed_message where message_id='capacity-old' and shadow_payload is not null",Integer.class));
        assertEquals(deadline,jdbc.queryForObject("select shadow_expires_at from processed_message where message_id='capacity-old'",java.sql.Timestamp.class).toInstant());
    }

    private void capture(IncomingMessage message, Instant processedAt, Instant expiresAt) {
        jdbc.update("""
                insert into processed_message(message_id,processed_at,shadow_payload,shadow_expires_at)
                values (?,?,?::jsonb,?)
                """,message.externalId().orElseThrow(),java.sql.Timestamp.from(processedAt),
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(message),java.sql.Timestamp.from(expiresAt));
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
