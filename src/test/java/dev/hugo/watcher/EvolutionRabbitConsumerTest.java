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

class EvolutionRabbitConsumerTest {
    private static final String BODY = """
            {"event":"messages.upsert","instance":"watcher-evolution","data":{"key":{"id":"evt-7","remoteJid":"15551234567@s.whatsapp.net"}}}
            """;

    @Test
    void acknowledgesOnlyAfterTheBusinessJobCompletes() throws Exception {
        var inbox = mock(InboxRepository.class);
        when(inbox.accept(any())).thenReturn(Acceptance.NEW);
        var jobs = mock(EvolutionJobProcessor.class);
        when(jobs.processAvailable()).thenReturn(1);
        var publisher = mock(RabbitEventPublisher.class);
        var consumer = new EvolutionRabbitConsumer(inbox, jobs, publisher, properties(), mock(ConnectionService.class),
                mock(NotificationService.class), mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);
        Message message = message("evt-7", 0);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        consumer.configurePipelineMetrics(new EvolutionPipelineMetrics(registry));
        consumer.receive(message, channel);
        assertEquals(1, registry.get("watcher.evolution.pipeline.events")
                .tags("stage", "inbox", "outcome", "accepted").counter().count());
        assertEquals(1, registry.get("watcher.evolution.pipeline.events")
                .tags("stage", "rabbit", "outcome", "consumed").counter().count());
        

        verify(jobs).processAvailable();
        verify(channel).basicAck(31L, false);
        verifyNoInteractions(publisher);
    }

    @Test
    void republishesFailedWorkToAConfirmedDelayedRetryQueueBeforeAcknowledging() throws Exception {
        var inbox = mock(InboxRepository.class);
        when(inbox.accept(any())).thenReturn(Acceptance.NEW);
        var jobs = mock(EvolutionJobProcessor.class);
        when(jobs.processAvailable()).thenReturn(0);
        when(jobs.isPending("messages.upsert", "evt-7")).thenReturn(true);
        var publisher = mock(RabbitEventPublisher.class);
        var consumer = new EvolutionRabbitConsumer(inbox, jobs, publisher, properties(), mock(ConnectionService.class),
                mock(NotificationService.class), mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);
        Message message = message("evt-7", 0);

        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        consumer.configurePipelineMetrics(new EvolutionPipelineMetrics(registry));
        consumer.receive(message, channel);

        assertEquals(1, registry.get("watcher.evolution.pipeline.events")
                .tags("stage", "rabbit", "outcome", "retry").counter().count());
        verify(publisher).publishDelayed("incoming.retry.1", "evt-7", BODY, Map.of("x-retry-count", 1), 1_000);
        verify(channel).basicAck(31L, false);
    }

    @Test
    void duplicateInboundStillDispatchesPendingNotificationsBeforeAck() throws Exception {
        var inbox = mock(InboxRepository.class);
        when(inbox.accept(any())).thenReturn(Acceptance.DUPLICATE);
        var jobs = mock(EvolutionJobProcessor.class);
        var notifications = mock(NotificationService.class);
        var consumer = new EvolutionRabbitConsumer(inbox, jobs, mock(RabbitEventPublisher.class), properties(),
                mock(ConnectionService.class), notifications, mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);

        consumer.receive(message("evt-7", 0), channel);

        var order = inOrder(notifications, channel);
        order.verify(notifications).flushAfterInbound(any());
        order.verify(channel).basicAck(31L, false);
    }

    @Test
    void failedDownstreamPublishRetainsInboundUntilRetryIsConfirmed() throws Exception {
        var inbox = mock(InboxRepository.class);
        when(inbox.accept(any())).thenReturn(Acceptance.DUPLICATE);
        var notifications = mock(NotificationService.class);
        doThrow(new IllegalStateException("publish failed")).when(notifications).flushAfterInbound(any());
        var publisher = mock(RabbitEventPublisher.class);
        doThrow(new IllegalStateException("retry publish failed")).when(publisher)
                .publishDelayed(anyString(), anyString(), anyString(), anyMap(), anyLong());
        var consumer = new EvolutionRabbitConsumer(inbox, mock(EvolutionJobProcessor.class), publisher, properties(),
                mock(ConnectionService.class), notifications, mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);

        assertThrows(IllegalStateException.class, () -> consumer.receive(message("evt-7", 0), channel));

        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void deadLettersAfterTheBoundedRetryCount() throws Exception {
        var inbox = mock(InboxRepository.class);
        when(inbox.accept(any())).thenThrow(new IllegalStateException("database unavailable"));
        var jobs = mock(EvolutionJobProcessor.class);
        var publisher = mock(RabbitEventPublisher.class);
        var consumer = new EvolutionRabbitConsumer(inbox, jobs, publisher, properties(), mock(ConnectionService.class),
                mock(NotificationService.class), mock(RabbitDeliveryMetrics.class));
        Channel channel = mock(Channel.class);
        Message message = message("evt-7", 3);

        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        consumer.configurePipelineMetrics(new EvolutionPipelineMetrics(registry));
        consumer.receive(message, channel);

        assertEquals(1, registry.get("watcher.evolution.pipeline.events")
                .tags("stage", "rabbit", "outcome", "dead_letter").counter().count());
        verify(channel).basicNack(31L, false, false);
        verifyNoInteractions(publisher);
    }

    private static Message message(String id, int retryCount) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(id);
        properties.setDeliveryTag(31L);
        properties.setHeader("x-retry-count", retryCount);
        return new Message(BODY.getBytes(StandardCharsets.UTF_8), properties);
    }

    private static EvolutionWebhookProperties properties() {
        var properties = new EvolutionWebhookProperties();
        properties.setInstance("watcher-evolution");
        return properties;
    }
}
