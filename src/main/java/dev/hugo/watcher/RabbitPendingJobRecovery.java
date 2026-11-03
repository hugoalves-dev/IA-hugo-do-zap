package dev.hugo.watcher;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import dev.hugo.watcher.telegram.TelegramWebhookInboxRepository;

import java.time.Clock;
import java.util.Map;

/** Replays pre-Rabbit PostgreSQL jobs once during cutover; ongoing ingress is RabbitMQ-driven. */
@Component
@ConditionalOnProperty(prefix = "watcher.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitPendingJobRecovery implements ApplicationRunner {
    private static final int BATCH_SIZE = 500;

    private final JobRepository jobs;
    private final RabbitEventPublisher publisher;
    private final Clock clock;
    private final TelegramWebhookInboxRepository telegramInbox;
    private final RabbitListenerEndpointRegistry listeners;

    public RabbitPendingJobRecovery(JobRepository jobs, RabbitEventPublisher publisher, Clock clock,
                                    TelegramWebhookInboxRepository telegramInbox,
                                    RabbitListenerEndpointRegistry listeners) {
        this.jobs = jobs;
        this.publisher = publisher;
        this.clock = clock;
        this.telegramInbox = telegramInbox;
        this.listeners = listeners;
    }

    @Override
    public void run(ApplicationArguments args) {
        jobs.preparePendingForRabbitStartup(clock.instant());
        while (true) {
            var batch = jobs.pendingForRabbit(BATCH_SIZE);
            if (batch.isEmpty()) break;
            for (var event : batch) {
                publisher.publish("incoming", event.messageId(), event.payload(),
                        Map.of("x-ingress-at", event.receivedAt().toString()));
                jobs.markRabbitBacklogConfirmed(event.jobId(), clock.instant());
            }
        }
        while (true) {
            var pendingUpdates = telegramInbox.pendingForRabbit(BATCH_SIZE);
            if (pendingUpdates.isEmpty()) break;
            for (var update : pendingUpdates) {
                String id = Long.toString(update.updateId());
                publisher.publish("admin", id, update.payload());
                telegramInbox.markRabbitBacklogConfirmed(update.updateId());
            }
        }
        listeners.start();
    }
}
