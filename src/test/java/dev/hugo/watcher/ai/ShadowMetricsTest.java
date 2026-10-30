package dev.hugo.watcher.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ShadowMetricsTest {
    @Test
    void countsShadowDecisionsAndProviderFailuresWithBoundedTags() {
        var registry = new SimpleMeterRegistry();
        {
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
        var registry = new SimpleMeterRegistry();
        {
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
        var registry = new SimpleMeterRegistry();
        {
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
    @Test void missingFinishReasonAndUsageProduceOnlySanitizedMetadata() {
        var registry = new SimpleMeterRegistry();
        var metrics = new ShadowMetrics(registry);
        metrics.reportedUsage("openrouter", """
                {"choices":[{"message":{"content":"synthetic private prompt"}}]}
                """);
        assertEquals(1, registry.get("watcher.shadow.provider.finish_reason")
                .tags("provider", "openrouter", "reason", "missing").counter().count());
        assertNull(registry.find("watcher.shadow.provider.tokens").counter());
        assertTrue(registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream())
                .noneMatch(tag -> tag.getValue().contains("synthetic private prompt")));
    }

    @Test void reasoningCanConsumeEntireOutputBudgetWithoutParsedContent() {
        var registry = new SimpleMeterRegistry();
        var metrics = new ShadowMetrics(registry);
        metrics.reportedUsage("openrouter", """
                {"choices":[{"finish_reason":"length","message":{"content":null}}],
                 "usage":{"completion_tokens":300,"completion_tokens_details":{"reasoning_tokens":300}}}
                """);
        assertEquals(300, registry.get("watcher.shadow.provider.tokens")
                .tags("provider", "openrouter", "direction", "output").counter().count());
        assertEquals(300, registry.get("watcher.shadow.provider.tokens")
                .tags("provider", "openrouter", "direction", "reasoning").counter().count());
        assertEquals(1, registry.get("watcher.shadow.provider.truncated")
                .tag("provider", "openrouter").counter().count());
    }

    @Test void reportsTruncationWhenLaterChoiceIsCutOff() {
        var registry = new SimpleMeterRegistry();
        var metrics = new ShadowMetrics(registry);
        metrics.reportedUsage("openrouter", """
                {"choices":[{"finish_reason":"stop"},{"finish_reason":"length"}]}
                """);
        assertEquals(1, registry.get("watcher.shadow.provider.truncated")
                .tag("provider", "openrouter").counter().count());
        assertEquals(1, registry.get("watcher.shadow.provider.finish_reason")
                .tags("provider", "openrouter", "reason", "length").counter().count());
    }

    @Test void recordsOutputTruncationAsSafeDecisionDiagnosticLabel() {
        var registry = new SimpleMeterRegistry();
        var metrics = new ShadowMetrics(registry);
        metrics.decision("classification_unavailable", "provider_output_truncated");
        metrics.providerFailure("openrouter", ClassificationProviderException.Kind.OUTPUT_TRUNCATED);
        assertEquals(1, registry.get("watcher.shadow.decisions")
                .tags("reason", "classification_unavailable", "diagnostic", "provider_output_truncated")
                .counter().count());
        assertEquals(1, registry.get("watcher.shadow.provider.failures")
                .tags("provider", "openrouter", "reason", "provider_output_truncated")
                .counter().count());
    }

    @Test void recordsBoundedFinishReasonAndReasoningUsage() {
        var registry = new SimpleMeterRegistry();
        var metrics = new ShadowMetrics(registry);
        metrics.reportedUsage("openrouter", """
                {"choices":[{"finish_reason":"length"}],
                 "usage":{"completion_tokens":300,"completion_tokens_details":{"reasoning_tokens":290}}}
                """);
        assertEquals(1, registry.get("watcher.shadow.provider.finish_reason")
                .tags("provider", "openrouter", "reason", "length").counter().count());
        assertEquals(290, registry.get("watcher.shadow.provider.tokens")
                .tags("provider", "openrouter", "direction", "reasoning").counter().count());
        assertEquals(1, registry.get("watcher.shadow.provider.truncated")
                .tag("provider", "openrouter").counter().count());
    }


}
