package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
    }

    @Autowired RabbitAdmin admin;
    @Autowired RabbitEventPublisher publisher;
    @Autowired org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listeners;
    @Autowired EvolutionWebhookController evolutionWebhook;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void startsDurableQueuesBeforeEnablingRabbitConsumers() {
        assertNotNull(admin.getQueueInfo("watcher.incoming"));
        assertNotNull(admin.getQueueInfo("watcher.admin"));
        assertNotNull(admin.getQueueInfo("watcher.notifications"));
        assertNotNull(admin.getQueueInfo("watcher.dead-letter"));
        assertNotNull(publisher);
        assertEquals(3, listeners.getListenerContainers().size());
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
