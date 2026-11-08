package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

class RabbitPendingJobRecoveryTest {
    @Test
    void confirmsEachLegacyPendingJobBeforeMarkingItMigrated() {
        JobRepository jobs = mock(JobRepository.class);
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        var telegramInbox = mock(dev.hugo.watcher.telegram.TelegramWebhookInboxRepository.class);
        var notificationOutbox = mock(NotificationOutboxRepository.class);
        var listeners = mock(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class);
        Instant receivedAt = Instant.parse("2026-11-03T12:00:00Z");
        var event = new JobRepository.RabbitPendingEvent(UUID.randomUUID(), "evt-7", "{\"event\":\"messages.upsert\"}", receivedAt);
        when(jobs.pendingForRabbit(500)).thenReturn(List.of(event), List.of());
        var recovery = new RabbitPendingJobRecovery(jobs, publisher,
                Clock.fixed(Instant.parse("2026-11-03T12:01:00Z"), ZoneOffset.UTC), telegramInbox,
                notificationOutbox, listeners);

        recovery.run(mock(ApplicationArguments.class));

        verify(jobs).preparePendingForRabbitStartup(Instant.parse("2026-11-03T12:01:00Z"));
        verify(publisher).publish("incoming", "evt-7", event.payload(), java.util.Map.of("x-ingress-at", receivedAt.toString()));
        verify(jobs).markRabbitBacklogConfirmed(event.jobId(), Instant.parse("2026-11-03T12:01:00Z"));
        verify(notificationOutbox).publishPendingActions();
        verify(listeners).start();
    }
}
