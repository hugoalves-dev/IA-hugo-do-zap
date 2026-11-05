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
