package dev.hugo.watcher;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationRabbitConsumerTest {
    @Test
    void acknowledgesOnlyAfterTheNamedNotificationIsDelivered() throws Exception {
        NotificationService notifications = mock(NotificationService.class);
        when(notifications.deliverQueuedAction(eq("archived_messages:abc"), any())).thenReturn(true);
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        NotificationRabbitConsumer consumer = new NotificationRabbitConsumer(notifications, publisher,
                mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);

        consumer.receive(message("archived_messages:abc", 0), channel);

        verify(notifications).deliverQueuedAction(eq("archived_messages:abc"), any());
        verify(channel).basicAck(17L, false);
        verifyNoInteractions(publisher);
    }

    @Test
    void usesConfirmedDelayedRetryAndAcknowledgesTheOriginalAfterTransientFailure() throws Exception {
        NotificationService notifications = mock(NotificationService.class);
        doThrow(new NotificationRetryException("rate limited", 4_250))
                .when(notifications).deliverQueuedAction(eq("admin:42"), any());
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        NotificationRabbitConsumer consumer = new NotificationRabbitConsumer(notifications, publisher,
                mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);

        consumer.receive(message("admin:42", 0), channel);

        verify(publisher).publishDelayed("notifications.retry.1", "admin:42", "admin:42",
                Map.of("x-retry-count", 1), 4_250);
        verify(channel).basicAck(17L, false);
    }

    @Test
    void defersWorkOnlyActionsDurablyWithoutConsumingTheTransientRetryBudget() throws Exception {
        NotificationService notifications = mock(NotificationService.class);
        doThrow(new NotificationDeferredException(86_400_000))
                .when(notifications).deliverQueuedAction(eq("archived_messages:work-only"), any());
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        NotificationRabbitConsumer consumer = new NotificationRabbitConsumer(notifications, publisher,
                mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);

        consumer.receive(message("archived_messages:work-only", 0), channel);

        verify(publisher).publishDelayed("notifications.deferred", "archived_messages:work-only",
                "archived_messages:work-only", Map.of("x-retry-count", 0), 86_400_000);
        verify(channel).basicAck(17L, false);
    }

    private static Message message(String actionKey, int retries) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(actionKey);
        properties.setDeliveryTag(17L);
        properties.setHeader("x-retry-count", retries);
        return new Message(actionKey.getBytes(StandardCharsets.UTF_8), properties);
    }
}
