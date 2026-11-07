package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "watcher.rabbitmq.enabled=true")
@Import(RabbitMessagingApplicationIntegrationTest.FakeTelegramConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RabbitMessagingApplicationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final GenericContainer<?> rabbit = new GenericContainer<>(DockerImageName.parse(
            "rabbitmq:4.4.0-management-alpine@sha256:b768d2e09a809b48458d6c3c32fc2894643a6bfafd76264c120373eff0ee6532"))
            .withExposedPorts(5672)
            .withEnv("RABBITMQ_DEFAULT_USER", "watcher-test")
            .withEnv("RABBITMQ_DEFAULT_PASS", "synthetic-test-password")
            .withEnv("RABBITMQ_DEFAULT_VHOST", "watcher");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "watcher-test");
        registry.add("spring.rabbitmq.password", () -> "synthetic-test-password");
        registry.add("spring.rabbitmq.virtual-host", () -> "watcher");
        registry.add("watcher.telegram-bot-token", () -> "synthetic-test-token");
        registry.add("watcher.telegram-chat-id", () -> "123456789");
        registry.add("watcher.evolution.webhook-token", () -> "synthetic-evolution-token");
        registry.add("watcher.evolution.instance", () -> "watcher-evolution");
        registry.add("watcher.whatsapp-actions.owner-jid", () -> "15550000000@s.whatsapp.net");
        registry.add("watcher.runtime.scheduling-enabled", () -> "false");
        registry.add("watcher.rabbitmq.native-evolution.enabled", () -> "true");
    }

    @Autowired RabbitAdmin admin;
    @Autowired RabbitEventPublisher publisher;
    @Autowired org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listeners;
    @Autowired EvolutionWebhookController evolutionWebhook;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired NotificationOutboxRepository outbox;
    private static final java.util.concurrent.ConcurrentLinkedQueue<TelegramDeliveryResult> OUTCOMES =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger SEND_ATTEMPTS =
            new java.util.concurrent.atomic.AtomicInteger();

    @TestConfiguration
    static class FakeTelegramConfiguration {
        @Bean @Primary TelegramTransport fakeTelegramTransport() {
            return new TelegramTransport() {
                @Override public TelegramDeliveryResult send(String text) { return sendAdmin(text, "{}"); }
                @Override public TelegramDeliveryResult sendAdmin(String text, String payload) {
                    SEND_ATTEMPTS.incrementAndGet();
                    TelegramDeliveryResult result = OUTCOMES.poll();
                    return result == null ? TelegramDeliveryResult.success() : result;
                }
            };
        }
    }

    @Test
    void startsDurableQueuesBeforeEnablingRabbitConsumers() {
        assertNotNull(admin.getQueueInfo("watcher.incoming"));
        assertNotNull(admin.getQueueInfo("watcher.admin"));
        assertNotNull(admin.getQueueInfo("watcher.notifications"));
        assertNotNull(admin.getQueueInfo("watcher.admin-replies"));
        assertNotNull(admin.getQueueInfo("watcher.admin-replies.retry.1"));
        assertNotNull(admin.getQueueInfo("watcher.prompt-results"));
        assertNotNull(admin.getQueueInfo("watcher.prompt-results.rate-limited"));
        assertNotNull(admin.getQueueInfo("watcher.prompt-results.retry.1"));
        assertNotNull(admin.getQueueInfo("watcher.dead-letter"));
        assertNotNull(publisher);
        assertEquals(5, listeners.getListenerContainers().size());
        assertTrue(listeners.getListenerContainers().stream().allMatch(
                org.springframework.amqp.rabbit.listener.MessageListenerContainer::isRunning));
        try {
            java.time.Instant availableAt = java.time.Instant.now().plusSeconds(30);
            String deferredQueueName = "watcher.notifications.deferred." + availableAt.toEpochMilli();
            publisher.publishDeferred(availableAt, "deferred-topology-check", "{}", java.util.Map.of());

            String queues = rabbit.execInContainer("rabbitmqctl", "list_queues", "-p", "watcher", "name", "arguments")
                    .getStdout();
            String retryQueue = java.util.Arrays.stream(queues.split("\\R"))
                    .filter(line -> line.startsWith("watcher.notifications.retry.1"))
                    .findFirst().orElseThrow();
            assertTrue(retryQueue.contains("x-message-ttl") && retryQueue.contains("1000"));
            String deferredQueue = java.util.Arrays.stream(queues.split("\\R"))
                    .filter(line -> line.startsWith(deferredQueueName))
                    .findFirst().orElseThrow();
            assertFalse(deferredQueue.contains("x-message-ttl"),
                    "long work-window deferrals use their own queue, not a shared short retry TTL");
        } catch (java.io.IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not inspect RabbitMQ retry topology", ex);
        }
    }

    @Test
    void confirmedEvolutionWebhookFlowsThroughRabbitConsumerIntoBusinessStorage() throws Exception {
        String body = """
                {"event":"contacts.upsert","instance":"watcher-evolution","data":[
                  {"remoteJid":"5511999912345@s.whatsapp.net","pushName":"Rabbit Loja"}]}
                """;

        var response = evolutionWebhook.receive("Bearer synthetic-evolution-token", body);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());

        boolean processed = false;
        for (int attempt = 0; attempt < 50; attempt++) {
            Integer count = jdbc.queryForObject("""
                    select count(*) from contact_identity
                    where canonical_jid='5511999912345@s.whatsapp.net' and display_name='Rabbit Loja'
                    """, Integer.class);
            if (count != null && count == 1) {
                processed = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(processed, "Rabbit consumer should persist the verified business name");
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from processing_jobs job join inbox_events inbox on inbox.id=job.inbox_event_id
                where inbox.event_type='contacts.upsert' and job.status='COMPLETED'
                """, Integer.class));
    }

    @Test
    void nativeEvolutionContactEventCanReachTheExistingConsumer() throws Exception {
        var template = new org.springframework.amqp.rabbit.core.RabbitTemplate(admin.getRabbitTemplate().getConnectionFactory());
        String body = """
                {"event":"contacts.update","instance":"watcher-evolution","data":[
                  {"remoteJid":"5511888812345@s.whatsapp.net","pushName":"Native Loja"}]}
                """;
        template.convertAndSend("watcher.evolution", "contacts.update", body, message -> {
            message.getMessageProperties().setMessageId("native-contact-42");
            return message;
        });
        boolean processed = false;
        for (int attempt = 0; attempt < 50; attempt++) {
            Integer count = jdbc.queryForObject("""
                    select count(*) from contact_identity
                    where canonical_jid='5511888812345@s.whatsapp.net' and display_name='Native Loja'
                    """, Integer.class);
            if (count != null && count == 1) { processed = true; break; }
            Thread.sleep(100);
        }
        assertTrue(processed, "native topic event should be routed into the watcher consumer");
    }

    @Test
    void adminReplyIsDeliveredWhileNotificationConsumerIsStopped() throws Exception {
        var notificationListener = listeners.getListenerContainer("notificationDelivery");
        assertNotNull(notificationListener);
        String key = "telegram_admin_reply:" + java.util.UUID.randomUUID();
        java.time.Instant now = java.time.Instant.now();
        notificationListener.stop();
        try {
            OUTCOMES.clear();
            jdbc.update("update telegram_delivery_rate_limit set next_allowed_at=null where singleton_id=1");
            outbox.enqueue(key, "telegram_admin_reply",
                    "{\"eventType\":\"telegram_admin_reply\",\"text\":\"teste\"}", "[]",
                    now, now.plusSeconds(600));
            boolean delivered = false;
            for (int attempt = 0; attempt < 100; attempt++) {
                String status = jdbc.queryForObject("select status from outbox_actions where action_key=?",
                        String.class, key);
                if ("ACKNOWLEDGED".equals(status)) { delivered = true; break; }
                Thread.sleep(100);
            }
            assertTrue(delivered, "administrator reply should not depend on the notification consumer");
        } finally {
            notificationListener.start();
        }
    }

    @Test
    void confirmedPromptResultIsConsumedWithTelegramRateLimitRetryAndDuplicateProtection() throws Exception {
        OUTCOMES.clear();
        SEND_ATTEMPTS.set(0);
        OUTCOMES.add(TelegramDeliveryResult.rateLimited(java.time.Duration.ofMillis(1_200)));
        OUTCOMES.add(TelegramDeliveryResult.success());
        var promptListener = listeners.getListenerContainer("promptResultDelivery");
        assertNotNull(promptListener);
        java.util.UUID jobId = java.util.UUID.randomUUID();
        String key = "prompt-generation-result:" + jobId;
        java.time.Instant now = java.time.Instant.now();

        promptListener.stop();
        try {
            jdbc.update("""
                    insert into prompt_generation_jobs
                        (job_id,draft_id,expected_draft_version,actor_user_id,chat_id,operation,instructions,status,
                         created_at,started_at,completed_at)
                    values (?,?,0,1001,2002,'REFINE','','FAILED',?,?,?)
                    """, jobId, java.util.UUID.randomUUID(), java.sql.Timestamp.from(now),
                    java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
            jdbc.update("update telegram_delivery_rate_limit set next_allowed_at=null where singleton_id=1");
            outbox.enqueue(key, "telegram_admin_reply",
                    "{\"eventType\":\"telegram_admin_reply\",\"text\":\"A geração falhou; revisão necessária.\"}",
                    "[]", now, now.plusSeconds(600));
            assertEquals("PENDING", jdbc.queryForObject(
                    "select result_delivery_status from prompt_generation_jobs where job_id=?", String.class, jobId));
            assertEquals(0, jdbc.queryForObject(
                    "select result_delivery_attempts from prompt_generation_jobs where job_id=?", Integer.class, jobId));
            assertNotNull(jdbc.queryForObject(
                    "select result_delivery_rabbit_confirmed_at from prompt_generation_jobs where job_id=?",
                    java.sql.Timestamp.class, jobId));
            assertTrue(admin.getQueueInfo("watcher.prompt-results").getMessageCount() >= 1);
        } finally {
            promptListener.start();
        }

        boolean acknowledged = false;
        for (int attempt = 0; attempt < 120; attempt++) {
            String status = jdbc.queryForObject(
                    "select result_delivery_status from prompt_generation_jobs where job_id=?", String.class, jobId);
            if ("ACKNOWLEDGED".equals(status)) { acknowledged = true; break; }
            Thread.sleep(100);
        }
        assertTrue(acknowledged, "confirmed result should reach Telegram through the prompt queue");
        assertEquals(2, jdbc.queryForObject(
                "select result_delivery_attempts from prompt_generation_jobs where job_id=?", Integer.class, jobId));
        assertEquals(2, SEND_ATTEMPTS.get(), "the fake transport should see one rate limit and one success");
        publisher.publish("prompt-results", key, key);
        Thread.sleep(500);
        assertEquals(2, SEND_ATTEMPTS.get(), "replayed/duplicate messages must not send an ACKed result again");
    }

    @Test
    void routesAnExhaustedManualAckDeliveryToTheDurableDeadLetterQueue() throws Exception {
        publisher.publish("incoming", "dlq-test", "{}", java.util.Map.of("x-retry-count", 3));

        boolean deadLettered = false;
        for (int attempt = 0; attempt < 50; attempt++) {
            var info = admin.getQueueInfo("watcher.dead-letter");
            if (info != null && info.getMessageCount() > 0) {
                deadLettered = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(deadLettered, "exhausted broker deliveries should be retained for operator review");
    }
}
