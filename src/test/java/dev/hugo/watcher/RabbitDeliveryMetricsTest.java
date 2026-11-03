package dev.hugo.watcher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RabbitDeliveryMetricsTest {
    @Test
    void recordsSeparateLowCardinalityLatencyTimersForAdminAndWhatsAppPaths() {
        var registry = new SimpleMeterRegistry();
        var metrics = new RabbitDeliveryMetrics(registry);

        metrics.record("admin", Instant.now().minusMillis(250));
        metrics.record("whatsapp", Instant.now().minusMillis(500));

        assertEquals(1, registry.get("watcher.telegram.delivery.latency").tag("path", "admin").timer().count());
        assertEquals(1, registry.get("watcher.telegram.delivery.latency").tag("path", "whatsapp").timer().count());
    }
}
