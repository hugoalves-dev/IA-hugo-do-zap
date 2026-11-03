package dev.hugo.watcher.telegram;

import com.rabbitmq.client.Channel;
import dev.hugo.watcher.NotificationService;
import dev.hugo.watcher.RabbitEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramRabbitConsumerTest {
    private static final String BODY = "{\"update_id\":42,\"message\":{\"message_id\":4,\"chat\":{\"id\":2002,\"type\":\"private\"},\"from\":{\"id\":1001},\"text\":\"/status\"}}";
    private static final String CALLBACK_BODY = "{\"update_id\":42,\"callback_query\":{\"id\":\"callback-1\",\"from\":{\"id\":1001},\"message\":{\"message_id\":4,\"chat\":{\"id\":2002,\"type\":\"private\"}},\"data\":\"token-1\"}}";

    @Test
    void acknowledgesAfterTheUpdateIsCommittedAndItsReplyIsDispatched() throws Exception {
        var inbox = mock(TelegramWebhookInboxRepository.class);
        when(inbox.insertIfAbsent(42L, BODY)).thenReturn(1);
        when(inbox.pendingByIdForUpdate(42L)).thenReturn(java.util.List.of(new TelegramWebhookInboxRepository.Item(42L, BODY)));
        var handler = mock(TelegramUpdateHandler.class);
        when(handler.handle(any())).thenReturn(AdminResult.accepted("ok"));
        var notifications = mock(NotificationService.class);
        var publisher = mock(RabbitEventPublisher.class);
        var consumer = consumer(inbox, handler, notifications, publisher);
        Channel channel = mock(Channel.class);

        consumer.receive(message(0), channel);

        verify(inbox).markDone(42L, null, "✅ Comando recebido.");
        verify(notifications).flushAfterInbound(any());
        verify(channel).basicAck(31L, false);
        verifyNoInteractions(publisher);
    }

    @Test
    void republishesHandlerFailuresToDelayedRetryBeforeAcknowledging() throws Exception {
        var inbox = mock(TelegramWebhookInboxRepository.class);
        when(inbox.insertIfAbsent(42L, BODY)).thenReturn(1);
        when(inbox.pendingByIdForUpdate(42L)).thenReturn(java.util.List.of(new TelegramWebhookInboxRepository.Item(42L, BODY)));
        var handler = mock(TelegramUpdateHandler.class);
        when(handler.handle(any())).thenThrow(new IllegalStateException("temporary failure"));
        var notifications = mock(NotificationService.class);
        var publisher = mock(RabbitEventPublisher.class);
        var consumer = consumer(inbox, handler, notifications, publisher);
        Channel channel = mock(Channel.class);

        consumer.receive(message(0), channel);

        verify(publisher).publishDelayed("admin.retry.1", "42", BODY, Map.of("x-retry-count", 1), 1_000);
        verify(channel).basicAck(31L, false);
        verify(inbox, never()).markDone(42L);
    }

    @Test
    void retryRedeliversPendingCallbackFeedbackWithoutReexecutingTheAdminCommand() throws Exception {
        var inbox = mock(TelegramWebhookInboxRepository.class);
        when(inbox.insertIfAbsent(42L, BODY)).thenReturn(0);
        when(inbox.pendingByIdForUpdate(42L)).thenReturn(java.util.List.of());
        when(inbox.pendingCallbackByIdForUpdate(42L)).thenReturn(
                java.util.List.of(new TelegramWebhookInboxRepository.CallbackItem(42L, "callback-1", "✅ Comando recebido.")));
        var handler = mock(TelegramUpdateHandler.class);
        var callbacks = mock(TelegramCallbackAcknowledger.class);
        when(callbacks.answerDurably("callback-1", "✅ Comando recebido.")).thenReturn(true);
        var notifications = mock(NotificationService.class);
        var publisher = mock(RabbitEventPublisher.class);
        var manager = transactionManager();
        var consumer = new TelegramRabbitConsumer(inbox, new org.springframework.transaction.support.TransactionTemplate(manager),
                handler, callbacks, notifications, publisher, mock(dev.hugo.watcher.RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);

        consumer.receive(message(0), channel);

        verify(callbacks).answerDurably("callback-1", "✅ Comando recebido.");
        verify(inbox).markCallbackAnswered(42L);
        verifyNoInteractions(handler);
        verify(channel).basicAck(31L, false);
    }

    @Test
    void callbackFeedbackFailureKeepsDurableFeedbackPendingAndRetriesWithoutReapplyingCommand() throws Exception {
        var inbox = mock(TelegramWebhookInboxRepository.class);
        when(inbox.insertIfAbsent(42L, CALLBACK_BODY)).thenReturn(1, 0);
        when(inbox.pendingByIdForUpdate(42L))
                .thenReturn(java.util.List.of(new TelegramWebhookInboxRepository.Item(42L, CALLBACK_BODY)),
                        java.util.List.of());
        when(inbox.pendingCallbackByIdForUpdate(42L)).thenReturn(
                java.util.List.of(new TelegramWebhookInboxRepository.CallbackItem(42L, "callback-1", "✅ Comando recebido.")));
        var handler = mock(TelegramUpdateHandler.class);
        when(handler.handle(any())).thenReturn(AdminResult.accepted("ok"));
        var callbacks = mock(TelegramCallbackAcknowledger.class);
        when(callbacks.answerDurably("callback-1", "✅ Comando recebido.")).thenReturn(false, true);
        var notifications = mock(NotificationService.class);
        var publisher = mock(RabbitEventPublisher.class);
        var manager = transactionManager();
        var consumer = new TelegramRabbitConsumer(inbox, new org.springframework.transaction.support.TransactionTemplate(manager),
                handler, callbacks, notifications, publisher, mock(dev.hugo.watcher.RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);

        consumer.receive(message(CALLBACK_BODY, 0), channel);
        consumer.receive(message(CALLBACK_BODY, 1), channel);

        verify(handler, times(1)).handle(any());
        verify(inbox).markDone(42L, "callback-1", "✅ Comando recebido.");
        verify(inbox).markCallbackAnswered(42L);
        verify(publisher).publishDelayed("admin.retry.1", "42", CALLBACK_BODY,
                Map.of("x-retry-count", 1), 1_000);
        verify(channel, times(2)).basicAck(31L, false);
    }

    private static TelegramRabbitConsumer consumer(TelegramWebhookInboxRepository inbox, TelegramUpdateHandler handler,
                                                   NotificationService notifications, RabbitEventPublisher publisher) {
        PlatformTransactionManager manager = transactionManager();
        var transactions = new org.springframework.transaction.support.TransactionTemplate(manager);
        return new TelegramRabbitConsumer(inbox, transactions, handler, mock(TelegramCallbackAcknowledger.class),
                notifications, publisher, mock(dev.hugo.watcher.RabbitDeliveryMetrics.class));
    }

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        doNothing().when(manager).commit(any());
        doNothing().when(manager).rollback(any());
        return manager;
    }

    private static Message message(int retryCount) {
        return message(BODY, retryCount);
    }

    private static Message message(String body, int retryCount) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("42");
        properties.setDeliveryTag(31L);
        properties.setHeader("x-retry-count", retryCount);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
