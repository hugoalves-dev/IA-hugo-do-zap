package dev.hugo.watcher;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Persists an inbound delivery idempotently, processes its business job, then ACKs RabbitMQ. */
@Component
@ConditionalOnProperty(prefix = "watcher.rabbitmq", name = "enabled", havingValue = "true")
public class EvolutionRabbitConsumer {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAY_MILLIS = {1_000, 5_000, 30_000};

    private final InboxRepository inbox;
    private final EvolutionJobProcessor jobs;
    private final RabbitEventPublisher publisher;
    private final EvolutionWebhookProperties properties;
    private final ConnectionService connections;
    private final NotificationService notifications;
    private final RabbitDeliveryMetrics metrics;

    public EvolutionRabbitConsumer(InboxRepository inbox, EvolutionJobProcessor jobs,
                                   RabbitEventPublisher publisher, EvolutionWebhookProperties properties,
                                   ConnectionService connections, NotificationService notifications,
                                   RabbitDeliveryMetrics metrics) {
        this.inbox = inbox;
        this.jobs = jobs;
        this.publisher = publisher;
        this.properties = properties;
        this.connections = connections;
        this.notifications = notifications;
        this.metrics = metrics;
    }

    @RabbitListener(queues = RabbitMessagingConfiguration.INCOMING_QUEUE,
            containerFactory = "rabbitListenerContainerFactory")
    public void receive(Message message, Channel channel) throws java.io.IOException {
        java.time.Instant ingressAt = ingressAt(message);
        RabbitDeliveryContext.set(ingressAt);
        try {
            process(message, channel, ingressAt);
        } finally {
            RabbitDeliveryContext.clear();
        }
    }

    private void process(Message message, Channel channel, java.time.Instant ingressAt) throws java.io.IOException {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        String id = message.getMessageProperties().getMessageId();
        try {
            Map<String, Object> payload = JSON.readValue(body, Map.class);
            String instance = payload.get("instance") instanceof String value ? value : null;
            String eventType = payload.get("event") instanceof String value ? value : null;
            if (!properties.getInstance().equals(instance) || eventType == null || id == null || id.isBlank())
                throw new IllegalArgumentException("invalid Evolution event envelope");

            if ("connection.update".equals(eventType)) {
                processConnectionUpdate(payload);
                notifications.flushAfterInbound(java.time.Instant.now());
                metrics.record("whatsapp", ingressAt);
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                return;
            }

            if (eventType.startsWith("chats.")) {
                inbox.quarantine("CHAT_STATE", id, "unverified_or_incomplete_state");
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                return;
            }

            inbox.accept(new EvolutionEvent("evolution", instance, eventType, id, payload));
            jobs.processAvailable();
            if (jobs.isPending(eventType, id))
                throw new IllegalStateException("inbound Evolution job was not completed");
            notifications.flushAfterInbound(java.time.Instant.now());
            metrics.record("whatsapp", ingressAt);
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (RuntimeException failure) {
            retryOrDeadLetter(message, channel, id == null || id.isBlank() ? UUID.randomUUID().toString() : id, body);
        }
    }

    private static java.time.Instant ingressAt(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("x-ingress-at");
        if (value instanceof String text) {
            try { return java.time.Instant.parse(text); }
            catch (RuntimeException ignored) { }
        }
        return java.time.Instant.now();
    }

    private void processConnectionUpdate(Map<String, Object> payload) {
        Object dataValue = payload.get("data");
        if (!(dataValue instanceof Map<?, ?> data)) throw new IllegalArgumentException("invalid connection event");
        String state = data.get("state") instanceof String value ? value : null;
        if (state == null || state.isBlank()) throw new IllegalArgumentException("invalid connection state");
        Integer statusReason = integer(data.get("statusReason"));
        if ("open".equals(state)) {
            connections.onState(new ConnectionEvent(ConnectionEvent.State.CONNECTED, null, "open", statusReason));
        } else if ("close".equals(state)) {
            boolean loggedOut = Integer.valueOf(401).equals(statusReason);
            connections.onState(new ConnectionEvent(loggedOut
                    ? ConnectionEvent.State.MANUAL_PAIRING_REQUIRED : ConnectionEvent.State.DISCONNECTED,
                    null, loggedOut ? "logged_out" : "connection_lost", statusReason));
        }
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try { return Integer.valueOf(text); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private void retryOrDeadLetter(Message message, Channel channel, String id, String body) throws java.io.IOException {
        int retries = retryCount(message);
        if (retries >= MAX_RETRIES) {
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
            return;
        }
        int nextRetry = retries + 1;
        publisher.publishDelayed("incoming.retry." + nextRetry, id, body,
                Map.of("x-retry-count", nextRetry), RETRY_DELAY_MILLIS[nextRetry - 1]);
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }

    private static int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("x-retry-count");
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }
}
