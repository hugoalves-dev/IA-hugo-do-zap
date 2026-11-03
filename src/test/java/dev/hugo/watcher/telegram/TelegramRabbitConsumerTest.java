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

        verify(inbox).markDone(42L);
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

    private static TelegramRabbitConsumer consumer(TelegramWebhookInboxRepository inbox, TelegramUpdateHandler handler,
                                                   NotificationService notifications, RabbitEventPublisher publisher) {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        doNothing().when(manager).commit(any());
        doNothing().when(manager).rollback(any());
        var transactions = new org.springframework.transaction.support.TransactionTemplate(manager);
        return new TelegramRabbitConsumer(inbox, transactions, handler, mock(TelegramCallbackAcknowledger.class),
                notifications, publisher, mock(dev.hugo.watcher.RabbitDeliveryMetrics.class));
    }

    private static Message message(int retryCount) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("42");
        properties.setDeliveryTag(31L);
        properties.setHeader("x-retry-count", retryCount);
        return new Message(BODY.getBytes(StandardCharsets.UTF_8), properties);
    }
}
