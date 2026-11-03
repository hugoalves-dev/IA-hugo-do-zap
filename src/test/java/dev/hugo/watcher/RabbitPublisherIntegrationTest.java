package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class RabbitPublisherIntegrationTest {
    private static final String USER = "watcher-test";
    private static final String PASSWORD = "synthetic-test-password";

    @Container
    static final GenericContainer<?> rabbit = new GenericContainer<>(DockerImageName.parse(
            "rabbitmq:4.4.0-management-alpine@sha256:b768d2e09a809b48458d6c3c32fc2894643a6bfafd76264c120373eff0ee6532"))
            .withExposedPorts(5672)
            .withEnv("RABBITMQ_DEFAULT_USER", USER)
            .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
            .withEnv("RABBITMQ_DEFAULT_VHOST", "watcher");

    @Test
    void confirmsPersistentRoutedEventsAndRejectsUnroutableEvents() throws Exception {
        try {
            assertEquals(0, rabbit.execInContainer("rabbitmqctl", "set_user_tags", USER).getExitCode());
            assertEquals(0, rabbit.execInContainer("rabbitmqctl", "set_permissions", "-p", "watcher", USER,
                    "^watcher\\.", "^watcher\\.", "^watcher\\.").getExitCode());
        } catch (java.io.IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not restrict the RabbitMQ application user", ex);
        }
        CachingConnectionFactory connection = new CachingConnectionFactory(rabbit.getHost(), rabbit.getMappedPort(5672));
        connection.setUsername(USER);
        connection.setPassword(PASSWORD);
        connection.setVirtualHost("watcher");
        connection.setPublisherConfirmType(ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        RabbitTemplate template = new RabbitTemplate(connection);
        template.setMandatory(true);
        template.setReturnsCallback(returned -> { });
        RabbitAdmin admin = new RabbitAdmin(connection);
        RabbitMessagingProperties properties = new RabbitMessagingProperties();
        properties.setEnabled(true);
        RabbitMessagingConfiguration topology = new RabbitMessagingConfiguration();
        DirectExchange exchange = topology.watcherEventExchange(properties);
        DirectExchange deadLetterExchange = topology.watcherDeadLetterExchange();
        var queue = topology.watcherIncomingQueue();
        var deadLetterQueue = topology.watcherDeadLetterQueue();
        admin.declareExchange(exchange);
        admin.declareExchange(deadLetterExchange);
        admin.declareQueue(queue);
        admin.declareQueue(deadLetterQueue);
        admin.declareBinding(topology.incomingBinding(queue, exchange));
        admin.declareBinding(topology.deadLetterBinding(deadLetterQueue, deadLetterExchange));

        properties.setExchange("watcher.events");
        properties.setConfirmTimeout(Duration.ofSeconds(5));
        RabbitEventPublisher publisher = new RabbitEventPublisher(template, properties);
        try {
            String id = UUID.randomUUID().toString();
            publisher.publish("incoming", id, "{\"event\":\"messages.upsert\"}");

            var received = template.receive(queue.getName(), 5_000);
            assertNotNull(received);
            assertEquals(MessageDeliveryMode.PERSISTENT, received.getMessageProperties().getReceivedDeliveryMode());
            assertEquals(id, received.getMessageProperties().getMessageId());
            IllegalStateException unroutable = assertThrows(IllegalStateException.class,
                    () -> publisher.publish("missing.route", "missing-1", "{}"));
            assertEquals("RabbitMQ did not route the event", unroutable.getMessage());

            Instant scheduledAt = Instant.now().plusSeconds(30);
            String deferredId = UUID.randomUUID().toString();
            publisher.publishDeferred(scheduledAt, deferredId, "{\"event\":\"deferred\"}", java.util.Map.of());
            var deferredQueue = admin.getQueueInfo("watcher.notifications.deferred." + scheduledAt.toEpochMilli());
            assertNotNull(deferredQueue);
            assertEquals(1, deferredQueue.getMessageCount());

            String restartId = UUID.randomUUID().toString();
            publisher.publish("incoming", restartId, "{\"event\":\"restart-recovery\"}");
            assertEquals(0, rabbit.execInContainer("rabbitmqctl", "stop_app").getExitCode());
            assertEquals(0, rabbit.execInContainer("rabbitmqctl", "start_app").getExitCode());

            CachingConnectionFactory afterRestart = new CachingConnectionFactory(
                    rabbit.getHost(), rabbit.getMappedPort(5672));
            afterRestart.setUsername(USER);
            afterRestart.setPassword(PASSWORD);
            afterRestart.setVirtualHost("watcher");
            try {
                RabbitTemplate recovered = new RabbitTemplate(afterRestart);
                var recoveredMessage = recovered.receive(queue.getName(), 5_000);
                assertNotNull(recoveredMessage);
                assertEquals(restartId, recoveredMessage.getMessageProperties().getMessageId());
                assertEquals(MessageDeliveryMode.PERSISTENT,
                        recoveredMessage.getMessageProperties().getReceivedDeliveryMode());
            } finally {
                afterRestart.destroy();
            }
        } finally {
            connection.destroy();
        }
    }
}
