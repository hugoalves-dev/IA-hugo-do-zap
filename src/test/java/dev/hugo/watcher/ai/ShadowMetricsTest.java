package dev.hugo.watcher.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ShadowMetricsTest {
    @Test
    void countsShadowDecisionsAndProviderFailuresWithBoundedTags() {
        try (var registry = new SimpleMeterRegistry()) {
            var metrics = new ShadowMetrics(registry);

            metrics.eligibleBatch();
            metrics.classification(ClassificationResult.Status.UNAVAILABLE);
            metrics.fallback();
            metrics.providerCall("openrouter", "timeout", Duration.ofMillis(25));
            metrics.decision("classification_unavailable", "consent_missing");

            assertEquals(1, registry.get("watcher.shadow.batches.eligible").counter().count());
            assertEquals(1, registry.get("watcher.shadow.classifications")
                    .tag("status", "UNAVAILABLE").counter().count());
            assertEquals(1, registry.get("watcher.shadow.fallbacks").counter().count());
            assertEquals(1, registry.get("watcher.shadow.provider.calls")
                    .tags("provider", "openrouter", "status", "timeout").counter().count());
            assertEquals(1, registry.get("watcher.shadow.provider.latency")
                    .tags("provider", "openrouter", "status", "timeout").timer().count());
            assertEquals(1, registry.get("watcher.shadow.decisions")
                    .tags("reason", "classification_unavailable", "diagnostic", "consent_missing")
                    .counter().count());

            metrics.decision("contact@lid/secret", "random-private-identifier");
            assertEquals(1, registry.get("watcher.shadow.decisions")
                    .tags("reason", "other", "diagnostic", "other").counter().count());
            assertNull(registry.find("watcher.shadow.decisions").tag("reason", "contact@lid/secret").counter());
        }
    }

    @Test
    void extractsProviderUsageWithoutLabelingMessageOrModelContent() {
        try (var registry = new SimpleMeterRegistry()) {
            var metrics = new ShadowMetrics(registry);

            metrics.reportedUsage("openrouter", """
                    {"usage":{"prompt_tokens":12,"completion_tokens":8,"cost":0.000023},
                     "choices":[{"message":{"content":"private conversation"}}]}
                    """);
            metrics.reportedUsage("gemini", """
                    {"usageMetadata":{"promptTokenCount":17,"candidatesTokenCount":6},
                     "candidates":[{"content":{"parts":[{"text":"private conversation"}]}}]}
                    """);

            assertEquals(12, registry.get("watcher.shadow.provider.tokens")
                    .tags("provider", "openrouter", "direction", "input").counter().count());
            assertEquals(8, registry.get("watcher.shadow.provider.tokens")
                    .tags("provider", "openrouter", "direction", "output").counter().count());
            assertEquals(17, registry.get("watcher.shadow.provider.tokens")
                    .tags("provider", "gemini", "direction", "input").counter().count());
            assertEquals(6, registry.get("watcher.shadow.provider.tokens")
                    .tags("provider", "gemini", "direction", "output").counter().count());
            assertEquals(0.000023, registry.get("watcher.shadow.provider.cost.usd")
                    .tag("provider", "openrouter").counter().count(), 1e-10);

            assertTrue(registry.getMeters().stream()
                    .flatMap(meter -> meter.getId().getTags().stream())
                    .noneMatch(tag -> tag.getValue().contains("private conversation")));
        }
    }

    @Test
    void malformedOrMissingUsageNeverBreaksProviderHandling() {
        try (var registry = new SimpleMeterRegistry()) {
            var metrics = new ShadowMetrics(registry);
            assertDoesNotThrow(() -> {
                metrics.reportedUsage("openrouter", "not json");
                metrics.reportedUsage("gemini", "{}");
                metrics.reportedUsage("openrouter", "{\"usage\":{\"cost\":-5,\"prompt_tokens\":-1}}");
                metrics.providerCall("untrusted-provider", "dynamic-status", Duration.ZERO);
            });
            assertNull(registry.find("watcher.shadow.provider.cost.usd").counter());
            assertEquals(1, registry.get("watcher.shadow.provider.calls")
                    .tags("provider", "other", "status", "other").counter().count());
        }
    }
}
