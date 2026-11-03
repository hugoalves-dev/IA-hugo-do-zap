package dev.hugo.watcher;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Exposes bounded queue-depth gauges, including the dead-letter queue. */
@Component
@ConditionalOnProperty(prefix = "watcher.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitQueueMetrics {
    private static final String[][] QUEUES = {
            {"incoming", RabbitMessagingConfiguration.INCOMING_QUEUE},
            {"admin", RabbitMessagingConfiguration.ADMIN_QUEUE},
            {"notifications", RabbitMessagingConfiguration.NOTIFICATION_QUEUE},
            {"dead-letter", RabbitMessagingConfiguration.DEAD_LETTER_QUEUE}
    };

    public RabbitQueueMetrics(MeterRegistry registry, RabbitAdmin admin) {
        for (String[] queue : QUEUES) {
            Gauge.builder("watcher.rabbitmq.queue.messages", admin, rabbit -> {
                try {
                    var info = rabbit.getQueueInfo(queue[1]);
                    return info == null ? 0 : info.getMessageCount();
                } catch (RuntimeException unavailable) {
                    return -1;
                }
            }).tag("queue", queue[0]).register(registry);
        }
    }
}
