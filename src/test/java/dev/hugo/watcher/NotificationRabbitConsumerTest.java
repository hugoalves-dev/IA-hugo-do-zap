package dev.hugo.watcher;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
    void usesTheFixedRetryBucketAndAcknowledgesTheOriginalAfterTransientFailure() throws Exception {
        NotificationService notifications = mock(NotificationService.class);
        doThrow(new NotificationRetryException("temporary failure", 4_250))
                .when(notifications).deliverQueuedAction(eq("admin:42"), any());
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        NotificationRabbitConsumer consumer = new NotificationRabbitConsumer(notifications, publisher,
                mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);

        consumer.receive(message("admin:42", 0), channel);

        verify(publisher).publishDelayed("notifications.retry.1", "admin:42", "admin:42",
                Map.of("x-retry-count", 1), 1_000);
        verify(channel).basicAck(17L, false);
    }

    @Test
    void retriesOutgoingTelegramAdminActionsThroughTheDedicatedQueue() throws Exception {
        String actionKey = "telegram_admin_reply:42";
        NotificationService notifications = mock(NotificationService.class);
        doThrow(new NotificationRetryException("temporary Telegram failure", 4_250))
                .when(notifications).deliverQueuedAction(eq(actionKey), any());
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        NotificationRabbitConsumer consumer = new NotificationRabbitConsumer(notifications, publisher,
                mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);

        consumer.receive(message(actionKey, 0), channel);

        verify(publisher).publishDelayed("admin-replies.retry.1", actionKey, actionKey,
                Map.of("x-retry-count", 1), 1_000);
        verify(channel).basicAck(17L, false);
    }

    @Test
    void replyRouteSurvivesRetryForAnUnrecognizedActionKey() throws Exception {
        String key = "custom-admin-action:42";
        NotificationService notifications = mock(NotificationService.class);
        doThrow(new NotificationRetryException("temporary failure", 1_000))
                .when(notifications).deliverQueuedAction(eq(key), any());
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        Channel channel = mock(Channel.class);
        Message event = message(key, 0);
        event.getMessageProperties().setConsumerQueue("watcher.admin-replies");

        new NotificationRabbitConsumer(notifications, publisher, mock(RabbitDeliveryMetrics.class))
                .receive(event, channel);

        verify(publisher).publishDelayed("admin-replies.retry.1", key, key,
                Map.of("x-retry-count", 1), 1_000);
        verify(channel).basicAck(17L, false);
    }

    @Test
    void calibrationResultRetryUsesAdminReplyQueue() throws Exception {
        String key = "prompt-calibration-result:42";
        NotificationService notifications = mock(NotificationService.class);
        doThrow(new NotificationRetryException("temporary failure", 1_000))
                .when(notifications).deliverQueuedAction(eq(key), any());
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        RabbitDeliveryMetrics metrics = mock(RabbitDeliveryMetrics.class);
        Channel channel = mock(Channel.class);

        new NotificationRabbitConsumer(notifications, publisher, metrics).receive(message(key, 0), channel);

        verify(publisher).publishDelayed("admin-replies.retry.1", key, key, Map.of("x-retry-count", 1), 1_000);
        verify(channel).basicAck(17L, false);
    }

    @Test
    void promptGenerationResultIsMeasuredAsAdminDelivery() throws Exception {
        String actionKey = "prompt-generation-result:50000000-0000-0000-0000-000000000050";
        NotificationService notifications = mock(NotificationService.class);
        when(notifications.deliverQueuedAction(eq(actionKey), any())).thenReturn(true);
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        RabbitDeliveryMetrics metrics = mock(RabbitDeliveryMetrics.class);
        NotificationRabbitConsumer consumer = new NotificationRabbitConsumer(notifications, publisher, metrics);
        Channel channel = mock(Channel.class);

        consumer.receive(message(actionKey, 0), channel);

        verify(metrics).record(eq("admin"), any());
        verify(channel).basicAck(17L, false);
        verifyNoInteractions(publisher);
    }

    @Test
    void promptGenerationResultDeadLetterIsPersistedWithoutPayload() throws Exception {
        String actionKey = "prompt-generation-result:50000000-0000-0000-0000-000000000051";
        NotificationService notifications = mock(NotificationService.class);
        doThrow(new NotificationRetryException("temporary Telegram transport failure", 1_000))
                .when(notifications).deliverQueuedAction(eq(actionKey), any());
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        NotificationRabbitConsumer consumer = new NotificationRabbitConsumer(notifications, publisher,
                mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);

        consumer.receive(message(actionKey, 3), channel);

        boolean recorded = mockingDetails(notifications).getInvocations().stream().anyMatch(invocation ->
                invocation.getMethod().getName().equals("markPromptGenerationResultDeadLetter")
                        && actionKey.equals(invocation.getArgument(0)));
        assertTrue(recorded, "the job's payload-free delivery summary must record a DLQ outcome");
        verify(channel).basicNack(17L, false, false);
        verifyNoInteractions(publisher);
    }

    @Test
    void honorsTelegramRetryAfterOnItsSeparateRateLimitQueue() throws Exception {
        NotificationService notifications = mock(NotificationService.class);
        doThrow(new NotificationRetryException("rate limited", 4_250, true))
                .when(notifications).deliverQueuedAction(eq("archived_messages:429"), any());
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        NotificationRabbitConsumer consumer = new NotificationRabbitConsumer(notifications, publisher,
                mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);

        consumer.receive(message("archived_messages:429", 0), channel);

        verify(publisher).publishDelayed("notifications.rate-limited", "archived_messages:429",
                "archived_messages:429", Map.of("x-retry-count", 1), 4_250);
        verify(channel).basicAck(17L, false);
    }

    @Test
    void defersRateLimitedTelegramAdminRepliesThroughTheDedicatedQueue() throws Exception {
        String actionKey = "telegram_admin_reply:429";
        NotificationService notifications = mock(NotificationService.class);
        doThrow(new NotificationRetryException("rate limited", 4_250, true))
                .when(notifications).deliverQueuedAction(eq(actionKey), any());
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        NotificationRabbitConsumer consumer = new NotificationRabbitConsumer(notifications, publisher,
                mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);

        consumer.receive(message(actionKey, 0), channel);

        verify(publisher).publishDelayed("admin-replies.rate-limited", actionKey, actionKey,
                Map.of("x-retry-count", 1), 4_250);
        verify(channel).basicAck(17L, false);
    }

    @Test
    void defersActiveDeliveryLeaseOnASeparateFixedLeaseQueue() throws Exception {
        NotificationService notifications = mock(NotificationService.class);
        doThrow(new NotificationDeferredException(1_000, NotificationDeferredException.Reason.ACTIVE_LEASE))
                .when(notifications).deliverQueuedAction(eq("archived_messages:lease"), any());
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        NotificationRabbitConsumer consumer = new NotificationRabbitConsumer(notifications, publisher,
                mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);

        consumer.receive(message("archived_messages:lease", 0), channel);

        verify(publisher).publishDelayed("notifications.lease", "archived_messages:lease",
                "archived_messages:lease", Map.of("x-retry-count", 0), 120_000);
        verify(channel).basicAck(17L, false);
    }

    @Test
    void defersLeasedTelegramAdminRepliesThroughTheDedicatedQueue() throws Exception {
        String actionKey = "telegram_admin_reply:lease";
        NotificationService notifications = mock(NotificationService.class);
        doThrow(new NotificationDeferredException(1_000, NotificationDeferredException.Reason.ACTIVE_LEASE))
                .when(notifications).deliverQueuedAction(eq(actionKey), any());
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        NotificationRabbitConsumer consumer = new NotificationRabbitConsumer(notifications, publisher,
                mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);

        consumer.receive(message(actionKey, 0), channel);

        verify(publisher).publishDelayed("admin-replies.lease", actionKey, actionKey,
                Map.of("x-retry-count", 0), 120_000);
        verify(channel).basicAck(17L, false);
    }

    @Test
    void defersWorkOnlyActionsDurablyWithoutConsumingTheTransientRetryBudget() throws Exception {
        NotificationService notifications = mock(NotificationService.class);
        Instant availableAt = Instant.now().plusMillis(86_400_000);
        doThrow(new NotificationDeferredException(86_400_000,
                NotificationDeferredException.Reason.WORK_WINDOW, availableAt))
                .when(notifications).deliverQueuedAction(eq("archived_messages:work-only"), any());
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        NotificationRabbitConsumer consumer = new NotificationRabbitConsumer(notifications, publisher,
                mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);

        consumer.receive(message("archived_messages:work-only", 0), channel);

        verify(publisher).publishDeferred(eq(availableAt), eq("archived_messages:work-only"),
                eq("archived_messages:work-only"), eq(Map.of("x-retry-count", 0)));
        verify(channel).basicAck(17L, false);
    }

    @Test
    void promptResultRetriesThroughDedicatedQueueAndPreservesItsActionKey() throws Exception {
        String key = "prompt-generation-result:50000000-0000-0000-0000-000000000052";
        NotificationService notifications = mock(NotificationService.class);
        doThrow(new NotificationRetryException("transport rejected", 1_000))
                .when(notifications).deliverQueuedAction(eq(key), any());
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        Channel channel = mock(Channel.class);

        new NotificationRabbitConsumer(notifications, publisher, mock(RabbitDeliveryMetrics.class))
                .receive(message(key, 0), channel);

        verify(publisher).publishDelayed("prompt-results.retry.1", key, key,
                Map.of("x-retry-count", 1), 1_000);
        verify(channel).basicAck(17L, false);
    }

    @Test
    void promptResultRateLimitAndLeaseRemainOnDedicatedRoutes() throws Exception {
        String key = "prompt-generation-result:50000000-0000-0000-0000-000000000053";
        NotificationService notifications = mock(NotificationService.class);
        doThrow(new NotificationRetryException("rate limited", 4_250, true),
                new NotificationDeferredException(1_000, NotificationDeferredException.Reason.ACTIVE_LEASE))
                .when(notifications).deliverQueuedAction(eq(key), any());
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        Channel channel = mock(Channel.class);
        NotificationRabbitConsumer consumer = new NotificationRabbitConsumer(notifications, publisher,
                mock(RabbitDeliveryMetrics.class));

        consumer.receive(message(key, 0), channel);
        consumer.receive(message(key, 1), channel);

        verify(publisher).publishDelayed("prompt-results.rate-limited", key, key,
                Map.of("x-retry-count", 1), 4_250);
        verify(publisher).publishDelayed("prompt-results.lease", key, key,
                Map.of("x-retry-count", 1), 120_000);
        verify(channel, times(2)).basicAck(17L, false);
    }

    @Test
    void duplicateBrokerMessageCannotCauseAnotherTelegramSend() throws Exception {
        String key = "prompt-generation-result:50000000-0000-0000-0000-000000000054";
        NotificationService notifications = mock(NotificationService.class);
        when(notifications.deliverQueuedAction(eq(key), any())).thenReturn(false);
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        Channel channel = mock(Channel.class);

        new NotificationRabbitConsumer(notifications, publisher, mock(RabbitDeliveryMetrics.class))
                .receive(message(key, 0), channel);

        verify(channel).basicAck(17L, false);
        verifyNoInteractions(publisher);
    }

    private static Message message(String actionKey, int retries) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(actionKey);
        properties.setDeliveryTag(17L);
        properties.setHeader("x-retry-count", retries);
        return new Message(actionKey.getBytes(StandardCharsets.UTF_8), properties);
    }
}
