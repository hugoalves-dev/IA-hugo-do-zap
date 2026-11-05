package dev.hugo.watcher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvolutionPipelineMetricsTest {
    @Test
    void emitsFixedCardinalityStageCountersWithoutLeakingEventIds() {
        try (var registry = new SimpleMeterRegistry()) {
            var metrics = new EvolutionPipelineMetrics(registry);
            metrics.record(EvolutionPipelineMetrics.Stage.WEBHOOK,
                    EvolutionPipelineMetrics.Outcome.RECEIVED, "sensitive-raw-id");
            metrics.record(EvolutionPipelineMetrics.Stage.RABBIT,
                    EvolutionPipelineMetrics.Outcome.PUBLISHED, "sensitive-raw-id");
            metrics.record(EvolutionPipelineMetrics.Stage.RABBIT,
                    EvolutionPipelineMetrics.Outcome.PUBLISHED, "a-different-id");

            assertEquals(1, registry.get("watcher.evolution.pipeline.events")
                    .tags("stage", "webhook", "outcome", "received").counter().count());
            assertEquals(2, registry.get("watcher.evolution.pipeline.events")
                    .tags("stage", "rabbit", "outcome", "published").counter().count());
            assertTrue(registry.get("watcher.evolution.last.upsert.received.epoch.seconds")
                    .gauge().value() > 0);
            assertEquals(2, registry.find("watcher.evolution.pipeline.events").counters().size());
            registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
                assertFalse(tag.getValue().contains("sensitive-raw-id"));
                assertFalse(tag.getValue().contains("a-different-id"));
            }));
            assertNotEquals("sensitive-raw-id", EvolutionPipelineMetrics.opaqueCorrelation("sensitive-raw-id"));
            assertEquals(24, EvolutionPipelineMetrics.opaqueCorrelation("sensitive-raw-id").length());
            assertEquals("unavailable", EvolutionPipelineMetrics.opaqueCorrelation(null));
        }
    }
}
