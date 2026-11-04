package dev.hugo.watcher.telegram;

import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.utility.BotUtils;
import com.rabbitmq.client.Channel;
import dev.hugo.watcher.NotificationService;
import dev.hugo.watcher.RabbitEventPublisher;
import dev.hugo.watcher.RabbitDeliveryContext;
import dev.hugo.watcher.RabbitDeliveryMetrics;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/** Applies Telegram admin updates idempotently and ACKs only after durable handling. */
@Component
@ConditionalOnProperty(prefix = "watcher.rabbitmq", name = "enabled", havingValue = "true")
public class TelegramRabbitConsumer {
    private static final Logger log = LoggerFactory.getLogger(TelegramRabbitConsumer.class);
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAY_MILLIS = {1_000, 5_000, 30_000};

    private final TelegramWebhookInboxRepository inbox;
    private final TransactionTemplate transactions;
    private final TelegramUpdateHandler handler;
    private final TelegramCallbackAcknowledger callbacks;
    private final NotificationService notifications;
    private final RabbitEventPublisher publisher;
    private final RabbitDeliveryMetrics metrics;

    @Autowired
    public TelegramRabbitConsumer(TelegramWebhookInboxRepository inbox, PlatformTransactionManager manager,
                                  TelegramUpdateHandler handler, TelegramCallbackAcknowledger callbacks,
                                  NotificationService notifications, RabbitEventPublisher publisher,
                                  RabbitDeliveryMetrics metrics) {
        this(inbox, new TransactionTemplate(manager), handler, callbacks, notifications, publisher, metrics);
    }

    TelegramRabbitConsumer(TelegramWebhookInboxRepository inbox, TransactionTemplate transactions,
                           TelegramUpdateHandler handler, TelegramCallbackAcknowledger callbacks,
                           NotificationService notifications, RabbitEventPublisher publisher,
                           RabbitDeliveryMetrics metrics) {
        this.inbox = inbox;
        this.transactions = transactions;
        this.handler = handler;
        this.callbacks = callbacks;
        this.notifications = notifications;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    @RabbitListener(queues = "watcher.admin", containerFactory = "rabbitListenerContainerFactory")
    public void receive(Message message, Channel channel) throws java.io.IOException {
        java.time.Instant ingressAt = ingressAt(message);
        RabbitDeliveryContext.set(ingressAt);
        String stage = "parse";
        try {
            process(message, channel);
        } finally {
            RabbitDeliveryContext.clear();
        }
    }

    private void process(Message message, Channel channel) throws java.io.IOException {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        String messageId = message.getMessageProperties().getMessageId();
        String stage = "parse";
        try {
            Update update = BotUtils.parseUpdate(body);
            if (update == null || update.updateId() == null || update.updateId() < 0)
                throw new IllegalArgumentException("invalid Telegram update");
            long updateId = update.updateId().longValue();
            stage = "inbox_persist";
            inbox.insertIfAbsent(updateId, body);
            stage = "admin_command";
            Processed processed = transactions.execute(status -> processPending(updateId));
            if (processed != null && processed.callbackId() != null) {
                stage = "callback_feedback";
                if (!callbacks.answerDurably(processed.callbackId(), processed.feedback()))
                    throw new IllegalStateException("Telegram callback acknowledgement was not confirmed");
                inbox.markCallbackAnswered(processed.updateId());
            }
            stage = "reply_dispatch";
            notifications.dispatchAdminRepliesAfterInbound(Instant.now());
            stage = "ack";
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (RuntimeException failure) {
            int retries = retryCount(message);
            log.error("Telegram admin update failed: messageId={}, retryCount={}, stage={}, cause={}",
                    safeMessageId(messageId), retries, stage, failure.getClass().getSimpleName(), safeFailure(failure));
            retryOrDeadLetter(message, channel, messageId == null ? "telegram-unknown" : messageId);
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

    private Processed processPending(long updateId) {
        var rows = inbox.pendingByIdForUpdate(updateId);
        if (rows.isEmpty()) {
            var pendingCallback = inbox.pendingCallbackByIdForUpdate(updateId);
            if (pendingCallback.isEmpty()) return null;
            var callback = pendingCallback.getFirst();
            return new Processed(callback.updateId(), callback.callbackId(), callback.feedback());
        }
        Update update = BotUtils.parseUpdate(rows.getFirst().payload());
        AdminResult result = handler.handle(update);
        var callback = update.callbackQuery();
        String callbackId = callback == null ? null : callback.id();
        String feedback = result.accepted() ? "✅ Comando recebido." : result.message();
        inbox.markDone(updateId, callbackId, feedback);
        return callbackId == null ? null : new Processed(updateId, callbackId, feedback);
    }

    private void retryOrDeadLetter(Message message, Channel channel, String id) throws java.io.IOException {
        int retries = retryCount(message);
        if (retries >= MAX_RETRIES) {
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
            return;
        }
        int nextRetry = retries + 1;
        try {
            publisher.publishDelayed("admin.retry." + nextRetry, id,
                    new String(message.getBody(), StandardCharsets.UTF_8),
                    Map.of("x-retry-count", nextRetry), RETRY_DELAY_MILLIS[nextRetry - 1]);
        } catch (RuntimeException publishFailure) {
            log.error("Telegram admin retry publication failed: messageId={}, retryCount={}, stage=retry_publish, cause={}",
                    safeMessageId(id), retries, publishFailure.getClass().getSimpleName(), safeFailure(publishFailure));
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
            return;
        }
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }

    private static String safeMessageId(String id) {
        return id != null && id.matches("[A-Za-z0-9_.:-]{1,80}") ? id : "redacted";
    }

    /** Keeps stack frames and cause types while dropping exception messages that may contain payloads or secrets. */
    private static RuntimeException safeFailure(Throwable failure) {
        Throwable root = failure;
        for (int depth = 0; depth < 16 && root.getCause() != null && root.getCause() != root; depth++)
            root = root.getCause();
        RuntimeException sanitized = new RuntimeException("failureType=" + failure.getClass().getName()
                + ", rootCauseType=" + root.getClass().getName());
        sanitized.setStackTrace(failure.getStackTrace());
        return sanitized;
    }

    private static int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("x-retry-count");
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private record Processed(long updateId, String callbackId, String feedback) { }
}
