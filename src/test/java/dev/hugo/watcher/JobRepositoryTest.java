package dev.hugo.watcher;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class JobRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private JobRepository jobs;

    @BeforeEach
    void migrateAndReset() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc.update("delete from processing_jobs");
        jdbc.update("delete from inbox_events");
        jobs = new JdbcJobRepository(jdbc, new DataSourceTransactionManager(dataSource));
    }

    @Test void twoConsumersNeverClaimTheSameJob() throws Exception {
        for (int i = 0; i < 4; i++) insertReadyJob("parallel-" + i);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return jobs.claim(2, Instant.now(), Duration.ofSeconds(30)); });
            var second = pool.submit(() -> { start.await(); return jobs.claim(2, Instant.now(), Duration.ofSeconds(30)); });
            start.countDown();
            var ids = new java.util.HashSet<java.util.UUID>();
            first.get().forEach(job -> ids.add(job.id()));
            second.get().forEach(job -> ids.add(job.id()));
            assertEquals(4, ids.size());
        }
    }

    @Test void reclaimsAJobAfterItsLeaseExpires() {
        insertReadyJob("lease-recovery");
        var now = Instant.now().plusSeconds(30);
        var first = jobs.claim(1, now, Duration.ofSeconds(10)).getFirst();
        var recovered = jobs.claim(1, now.plusSeconds(11), Duration.ofSeconds(10)).getFirst();
        assertEquals(first.id(), recovered.id());
        assertNotEquals(first.leaseToken(), recovered.leaseToken());
    }

    @Test void anExpiredLeaseCannotCompleteTheReclaimedJob() {
        insertReadyJob("stale-lease");
        var now = Instant.now().plusSeconds(30);
        var oldLease = jobs.claim(1, now, Duration.ofSeconds(10)).getFirst();
        var newLease = jobs.claim(1, now.plusSeconds(11), Duration.ofSeconds(10)).getFirst();
        assertThrows(IllegalStateException.class, () -> jobs.complete(oldLease.id(), oldLease.leaseToken()));
        assertDoesNotThrow(() -> jobs.complete(newLease.id(), newLease.leaseToken()));
    }

    @Test void doesNotClaimJobAfterItsInboxRetentionDeadline() {
        insertReadyJob("expired-inbox");
        jdbc.update("update inbox_events set expires_at=now()-interval '1 second' where external_id='expired-inbox'");

        assertTrue(jobs.claim(1, Instant.now(), Duration.ofSeconds(10)).isEmpty());
    }

    private void insertReadyJob(String externalId) {
        long inboxId = jdbc.queryForObject("insert into inbox_events(source,event_type,external_id,payload) values ('evolution','messages.upsert',?, '{}'::jsonb) returning id", Long.class, externalId);
        jdbc.update("insert into processing_jobs(inbox_event_id,status) values (?, 'READY')", inboxId);
    }
}
