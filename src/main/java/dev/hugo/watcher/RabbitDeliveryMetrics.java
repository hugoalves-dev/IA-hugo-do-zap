package dev.hugo.watcher;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** End-to-end receipt-to-Telegram latency with only a bounded path label. */
@Component
public class RabbitDeliveryMetrics {
    private final MeterRegistry registry;

    public RabbitDeliveryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String path, Instant ingressAt) {
        if (!"admin".equals(path) && !"whatsapp".equals(path)) return;
        Instant start = ingressAt == null ? Instant.now() : ingressAt;
        Duration elapsed = Duration.between(start, Instant.now());
        if (elapsed.isNegative()) elapsed = Duration.ZERO;
        Timer.builder("watcher.telegram.delivery.latency")
                .tag("path", path)
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry)
                .record(elapsed);
    }
}
