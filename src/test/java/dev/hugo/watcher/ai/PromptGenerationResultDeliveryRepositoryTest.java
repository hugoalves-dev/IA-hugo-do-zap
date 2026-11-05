package dev.hugo.watcher.ai;

import dev.hugo.watcher.NotificationOutboxRepository;
import dev.hugo.watcher.RetentionService;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class PromptGenerationResultDeliveryRepositoryTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private NotificationOutboxRepository outbox;

    @BeforeEach void migrate() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).cleanDisabled(false).load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        jdbc.update("delete from outbox_actions");
        jdbc.update("delete from prompt_generation_jobs");
        jdbc.update("update telegram_delivery_rate_limit set next_allowed_at=null");
        outbox = new NotificationOutboxRepository(jdbc, transactions);
    }

    @Test void mirrorsOutboxLifecycleWithoutPayload() {
        Instant now = Instant.now();
        UUID jobId = insertCompletedJob(now);
        String actionKey = "prompt-generation-result:" + jobId;
        outbox.enqueue(actionKey, "telegram_admin_reply", "{\"text\":\"safe result\"}", "[]",
                now.minusSeconds(1), now.plus(Duration.ofDays(1)));

        assertDelivery(jobId, "PENDING", 0, null);
        var firstClaim = outbox.claimByKey(actionKey, now);
        assertNotNull(firstClaim);
        assertDelivery(jobId, "PROCESSING", 1, null);

        outbox.retry(firstClaim, now, "DeliveryFailure");
        assertDelivery(jobId, "PENDING", 1, "DeliveryFailure");
        makeTelegramRateLimitReady();
        jdbc.update("update outbox_actions set available_at=clock_timestamp()-interval '1 second' where action_key=?", actionKey);
        var secondClaim = outbox.claimByKey(actionKey, now.plusSeconds(1));
        assertNotNull(secondClaim);
        assertDelivery(jobId, "PROCESSING", 2, "DeliveryFailure");

        Instant acknowledgedAt = Instant.now();
        assertTrue(outbox.acknowledge(secondClaim, acknowledgedAt));
        assertDelivery(jobId, "ACKNOWLEDGED", 2, null);
        assertNotNull(deliveryTimestamp(jobId));
        assertEquals("{}", jdbc.queryForObject("select payload::text from outbox_actions where action_key=?", String.class, actionKey));

        new RetentionService(jdbc, transactions).purge(acknowledgedAt.plusSeconds(1));
        assertDelivery(jobId, "ACKNOWLEDGED", 2, null);
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_actions where action_key=?", Integer.class, actionKey));
    }

    @Test void retentionPreservesAcknowledgedStatusAndExpiresUnacknowledgedAction() {
        Instant now = Instant.now();
        UUID acknowledgedJob = insertCompletedJob(now.minusSeconds(10));
        UUID pendingJob = insertCompletedJob(now.minus(Duration.ofDays(2)));
        String acknowledgedKey = "prompt-generation-result:" + acknowledgedJob;
        String pendingKey = "prompt-generation-result:" + pendingJob;
        outbox.enqueue(acknowledgedKey, "telegram_admin_reply", "{\"text\":\"safe result\"}", "[]",
                now.minusSeconds(2), now.plus(Duration.ofDays(1)));
        var claim = outbox.claimByKey(acknowledgedKey, now);
        assertNotNull(claim);
        assertTrue(outbox.acknowledge(claim, now));
        outbox.enqueue(pendingKey, "telegram_admin_reply", "{\"text\":\"safe result\"}", "[]",
                now.minus(Duration.ofDays(2)), now.minusSeconds(1));
        assertDelivery(pendingJob, "PENDING", 0, null);

        new RetentionService(jdbc, transactions).purge(now.plusSeconds(1));

        assertDelivery(acknowledgedJob, "ACKNOWLEDGED", 1, null);
        assertDelivery(pendingJob, "EXPIRED", 0, null);
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_actions where action_key in (?,?)",
                Integer.class, acknowledgedKey, pendingKey));
    }

    @Test void resultSummaryAllowsSuppressedAndDeadLetterStates() {
        UUID jobId = insertCompletedJob(Instant.now());

        assertDoesNotThrow(() -> jdbc.update("update prompt_generation_jobs set result_delivery_status='SUPPRESSED' where job_id=?", jobId));
        assertDoesNotThrow(() -> jdbc.update("update prompt_generation_jobs set result_delivery_status='DEAD_LETTER' where job_id=?", jobId));
    }

    private void makeTelegramRateLimitReady() {
        jdbc.update("update telegram_delivery_rate_limit set next_allowed_at=clock_timestamp()-interval '1 second'");
    }

    private UUID insertCompletedJob(Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into prompt_generation_jobs
                    (job_id,draft_id,expected_draft_version,actor_user_id,chat_id,operation,instructions,status,
                     created_at,started_at,completed_at)
                values (?,?,0,1001,2002,'GENERATE','','COMPLETE',?,?,?)
                """, id, UUID.randomUUID(), java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt));
        return id;
    }

    private void assertDelivery(UUID jobId, String status, int attempts, String error) {
        assertEquals(status, jdbc.queryForObject("select result_delivery_status from prompt_generation_jobs where job_id=?", String.class, jobId));
        assertEquals(attempts, jdbc.queryForObject("select result_delivery_attempts from prompt_generation_jobs where job_id=?", Integer.class, jobId));
        assertEquals(error, jdbc.queryForObject("select result_delivery_last_error from prompt_generation_jobs where job_id=?", String.class, jobId));
    }

    private java.sql.Timestamp deliveryTimestamp(UUID jobId) {
        return jdbc.queryForObject("select result_delivery_expires_at from prompt_generation_jobs where job_id=?",
                java.sql.Timestamp.class, jobId);
    }
}
