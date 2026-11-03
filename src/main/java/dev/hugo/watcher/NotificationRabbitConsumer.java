package dev.hugo.watcher;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/** Delivers only the action identified by the broker message, never by a PostgreSQL poll. */
@Component
@ConditionalOnProperty(prefix = "watcher.rabbitmq", name = "enabled", havingValue = "true")
public class NotificationRabbitConsumer {
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAY_MILLIS = {1_000, 5_000, 30_000};

    private final NotificationService notifications;
    private final RabbitEventPublisher publisher;
    private final RabbitDeliveryMetrics metrics;

    public NotificationRabbitConsumer(NotificationService notifications, RabbitEventPublisher publisher,
                                      RabbitDeliveryMetrics metrics) {
        this.notifications = notifications;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    @RabbitListener(queues = RabbitMessagingConfiguration.NOTIFICATION_QUEUE,
            containerFactory = "rabbitListenerContainerFactory")
    public void receive(Message message, Channel channel) throws java.io.IOException {
        String actionKey = new String(message.getBody(), StandardCharsets.UTF_8);
        String messageId = message.getMessageProperties().getMessageId();
        Instant ingressAt = ingressAt(message);
        RabbitDeliveryContext.set(ingressAt);
        try {
            boolean delivered = notifications.deliverQueuedAction(actionKey, Instant.now());
            if (delivered) metrics.record(isAdminAction(actionKey) ? "admin" : "whatsapp", ingressAt);
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (NotificationDeferredException deferred) {
            String deferredRoute = isAdminAction(actionKey) ? "admin.deferred" : "notifications.deferred";
            publisher.publishDelayed(deferredRoute, messageId == null ? actionKey : messageId,
                    actionKey, deferredHeaders(message), deferred.delayMillis());
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (NotificationRetryException retry) {
            retryOrDeadLetter(message, channel, messageId == null ? actionKey : messageId, actionKey, retry.delayMillis());
        } catch (RuntimeException failure) {
            int retries = retryCount(message);
            long delay = RETRY_DELAY_MILLIS[Math.min(retries, RETRY_DELAY_MILLIS.length - 1)];
            retryOrDeadLetter(message, channel, messageId == null ? actionKey : messageId, actionKey, delay);
        } finally {
            RabbitDeliveryContext.clear();
        }
    }

    private static Instant ingressAt(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("x-ingress-at");
        if (value instanceof String text) {
            try { return Instant.parse(text); }
            catch (RuntimeException ignored) { }
        }
        return Instant.now();
    }

    private static boolean isAdminAction(String actionKey) {
        return actionKey.startsWith("telegram_admin_reply:") || actionKey.startsWith("telegram_admin_edit:");
    }

    private static Map<String, Object> deferredHeaders(Message message) {
        java.util.Map<String, Object> headers = new java.util.HashMap<>();
        Object retries = message.getMessageProperties().getHeaders().get("x-retry-count");
        if (retries != null) headers.put("x-retry-count", retries);
        Object ingress = message.getMessageProperties().getHeaders().get("x-ingress-at");
        if (ingress != null) headers.put("x-ingress-at", ingress);
        return headers;
    }

    private void retryOrDeadLetter(Message message, Channel channel, String id, String body, long delayMillis)
            throws java.io.IOException {
        int retries = retryCount(message);
        if (retries >= MAX_RETRIES) {
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
            return;
        }
        int nextRetry = retries + 1;
        publisher.publishDelayed("notifications.retry." + nextRetry, id, body,
                Map.of("x-retry-count", nextRetry), delayMillis);
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }

    private static int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("x-retry-count");
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }
}
