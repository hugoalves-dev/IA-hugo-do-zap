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
    private static final long LEASE_RETRY_DELAY_MILLIS = 120_000;

    private final NotificationService notifications;
    private final RabbitEventPublisher publisher;
    private final RabbitDeliveryMetrics metrics;

    public NotificationRabbitConsumer(NotificationService notifications, RabbitEventPublisher publisher,
                                      RabbitDeliveryMetrics metrics) {
        this.notifications = notifications;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    @RabbitListener(id = "notificationDelivery", queues = RabbitMessagingConfiguration.NOTIFICATION_QUEUE,
            containerFactory = "rabbitListenerContainerFactory")
    public void receive(Message message, Channel channel) throws java.io.IOException {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        final RabbitDeliveryJob job;
        try {
            job = RabbitDeliveryJob.parse(body);
        } catch (IllegalArgumentException malformed) {
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
            return;
        }
        String actionKey = job == null ? body : job.actionKey();
        String messageId = message.getMessageProperties().getMessageId();
        Instant ingressAt = ingressAt(message);
        RabbitDeliveryContext.set(ingressAt);
        try {
            boolean delivered = job == null
                    ? notifications.deliverQueuedAction(actionKey, Instant.now())
                    : notifications.deliverQueuedAction(job, Instant.now());
            if (delivered) metrics.record("notifications".equals(route(actionKey, message)) ? "whatsapp" : "admin", ingressAt);
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (NotificationDeferredException deferred) {
            String id = messageId == null ? actionKey : messageId;
            if (deferred.reason() == NotificationDeferredException.Reason.WORK_WINDOW
                    || deferred.reason() == NotificationDeferredException.Reason.SCHEDULED) {
                Instant availableAt = deferred.availableAt() == null
                        ? Instant.now().plusMillis(deferred.delayMillis()) : deferred.availableAt();
                if ("notifications".equals(route(actionKey, message)))
                    publisher.publishDeferred(availableAt, id, body, deferredHeaders(message));
                else
                    publisher.publishDeferred(route(actionKey, message), availableAt, id, body, deferredHeaders(message));
            } else {
                String deferredRoute = deferredRoute(actionKey, message, deferred.reason());
                long delay = deferred.reason() == NotificationDeferredException.Reason.ACTIVE_LEASE
                        ? LEASE_RETRY_DELAY_MILLIS : deferred.delayMillis();
                publisher.publishDelayed(deferredRoute, id, body, deferredHeaders(message), delay);
            }
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (NotificationRetryException retry) {
            int retries = retryCount(message);
            if (retry.rateLimited()) {
                retryOrDeadLetter(message, channel, messageId == null ? actionKey : messageId, actionKey, body,
                        retry.delayMillis(), route(actionKey, message) + ".rate-limited");
            } else {
                long delay = RETRY_DELAY_MILLIS[Math.min(retries, RETRY_DELAY_MILLIS.length - 1)];
                retryOrDeadLetter(message, channel, messageId == null ? actionKey : messageId, actionKey, body, delay,
                        retryRoute(actionKey, message, retries + 1));
            }
        } catch (RuntimeException failure) {
            int retries = retryCount(message);
            long delay = RETRY_DELAY_MILLIS[Math.min(retries, RETRY_DELAY_MILLIS.length - 1)];
            retryOrDeadLetter(message, channel, messageId == null ? actionKey : messageId, actionKey, body, delay,
                    retryRoute(actionKey, message, retries + 1));
        } finally {
            RabbitDeliveryContext.clear();
        }
    }

    /** Keeps administrator replies responsive when notification delivery is backlogged. */
    @RabbitListener(id = "adminReplyDelivery", queues = RabbitMessagingConfiguration.ADMIN_REPLY_QUEUE,
            containerFactory = "rabbitListenerContainerFactory")
    public void receiveAdminReply(Message message, Channel channel) throws java.io.IOException {
        receive(message, channel);
    }

    /** Dedicated consumer prevents unrelated notification backlogs from starving prompt results. */
    @RabbitListener(id = "promptResultDelivery", queues = RabbitMessagingConfiguration.PROMPT_RESULT_QUEUE,
            containerFactory = "rabbitListenerContainerFactory")
    public void receivePromptResult(Message message, Channel channel) throws java.io.IOException {
        receive(message, channel);
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
        return actionKey.startsWith("telegram_admin_reply:") || actionKey.startsWith("telegram_admin_edit:")
                || actionKey.startsWith("prompt-generation-result:")
                || actionKey.startsWith("prompt-calibration-result:");
    }

    private static String deferredRoute(String actionKey, Message message, NotificationDeferredException.Reason reason) {
        return switch (reason) {
            case WORK_WINDOW, SCHEDULED -> throw new IllegalArgumentException("scheduled actions use a date-specific queue");
            case RATE_LIMIT -> route(actionKey, message) + ".rate-limited";
            case ACTIVE_LEASE -> route(actionKey, message) + ".lease";
        };
    }

    private static String route(String actionKey, Message message) {
        String queue = message.getMessageProperties().getConsumerQueue();
        if (actionKey.startsWith("prompt-generation-result:")
                || RabbitMessagingConfiguration.PROMPT_RESULT_QUEUE.equals(queue)) return "prompt-results";
        return isAdminAction(actionKey) || RabbitMessagingConfiguration.ADMIN_REPLY_QUEUE.equals(queue)
                ? "admin-replies" : "notifications";
    }

    private static String retryRoute(String actionKey, Message message, int attempt) {
        return route(actionKey, message) + ".retry." + Math.min(3, attempt);
    }

    private static Map<String, Object> deferredHeaders(Message message) {
        java.util.Map<String, Object> headers = new java.util.HashMap<>();
        Object retries = message.getMessageProperties().getHeaders().get("x-retry-count");
        if (retries != null) headers.put("x-retry-count", retries);
        Object ingress = message.getMessageProperties().getHeaders().get("x-ingress-at");
        if (ingress != null) headers.put("x-ingress-at", ingress);
        return headers;
    }

    private void retryOrDeadLetter(Message message, Channel channel, String id, String actionKey, String body, long delayMillis,
                                   String retryRoute) throws java.io.IOException {
        int retries = retryCount(message);
        if (retries >= MAX_RETRIES) {
            if (actionKey.startsWith("prompt-generation-result:"))
                notifications.markPromptGenerationResultDeadLetter(actionKey);
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
            return;
        }
        int nextRetry = retries + 1;
        publisher.publishDelayed(retryRoute, id, body,
                Map.of("x-retry-count", nextRetry), delayMillis);
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }

    private static int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("x-retry-count");
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }
}
