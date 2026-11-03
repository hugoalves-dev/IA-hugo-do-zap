package dev.hugo.watcher;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Native INSERT ... ON CONFLICT RETURNING must atomically admit one job per source event. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcInboxRepositoryContractTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private JdbcInboxRepository inbox;
    private TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        var datasource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(datasource).load().migrate();
        jdbc = new JdbcTemplate(datasource);
        inbox = new JdbcInboxRepository(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        jdbc.update("delete from processing_jobs");
        jdbc.update("delete from inbox_events");
    }

    @Test
    void duplicateWebhookUnderConcurrentTransactionsCreatesExactlyOneJob() throws Exception {
        var event = event("parallel-inbox");
        int workers = 8;
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(workers)) {
            var futures = new ArrayList<java.util.concurrent.Future<Acceptance>>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("Webhook race did not start");
                    return transactions.execute(status -> inbox.accept(event));
                }));
            }
            start.countDown();
            int accepted = 0;
            int duplicates = 0;
            for (var future : futures) {
                Acceptance result = future.get(40, TimeUnit.SECONDS);
                if (result == Acceptance.NEW) accepted++;
                if (result == Acceptance.DUPLICATE) duplicates++;
            }
            assertEquals(1, accepted);
            assertEquals(workers - 1, duplicates);
        }
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from inbox_events where external_id=?", Integer.class, "parallel-inbox"));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from processing_jobs job
                join inbox_events inbox on inbox.id=job.inbox_event_id
                where inbox.external_id=?
                """, Integer.class, "parallel-inbox"));
    }

    @Test
    void failedIntakeTransactionLeavesNoInboxTombstoneAndAllowsCleanRetry() {
        var event = event("rollback-inbox");
        assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
            assertEquals(Acceptance.NEW, inbox.accept(event));
            throw new IllegalStateException("synthetic failure before commit");
        }));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from inbox_events where external_id=?", Integer.class, "rollback-inbox"));
        assertEquals(0, jdbc.queryForObject("select count(*) from processing_jobs", Integer.class));

        assertEquals(Acceptance.NEW, transactions.execute(status -> inbox.accept(event)));
        assertEquals(Acceptance.DUPLICATE, transactions.execute(status -> inbox.accept(event)));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from inbox_events where external_id=?", Integer.class, "rollback-inbox"));
        assertEquals(1, jdbc.queryForObject("select count(*) from processing_jobs", Integer.class));
    }

    private static EvolutionEvent event(String id) {
        return new EvolutionEvent("evolution", "watcher-test", "messages.upsert", id,
                Map.of("source", "synthetic-test", "id", id));
    }
}
