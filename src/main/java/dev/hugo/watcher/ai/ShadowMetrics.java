package dev.hugo.watcher.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shadow-mode metrics only. Never uses message text, chat identifiers, batch IDs or
 * arbitrary provider data as metric names or labels.
 */
@Component
public final class ShadowMetrics {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> REASONS = Set.of(
            "ai_classification_disabled", "archive_scope_blocked", "archive_scope_excluded",
            "archive_state_unknown", "authorization_denied", "budget_exceeded",
            "classification_unavailable", "classification_uncertain", "configuration_invalid",
            "consent_missing", "context_insufficient", "feature_disabled",
            "group_trigger_missing", "kill_switch_enabled", "no_eligible_text",
            "no_request_detected", "privacy_route_unavailable", "provider_timeout",
            "provider_unavailable", "result_invalid", "provider_output_truncated", "state_or_scope_ineligible",
            "template_missing", "cooldown_active", "not_available", "no_action",
            "simulation_only"
    );

    private final MeterRegistry registry;

    public ShadowMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void eligibleBatch() {
        registry.counter("watcher.shadow.batches.eligible").increment();
    }

    public void classification(ClassificationResult.Status status) {
        registry.counter("watcher.shadow.classifications", "status",
                status == null ? "UNAVAILABLE" : status.name()).increment();
    }

    public void fallback() {
        registry.counter("watcher.shadow.fallbacks").increment();
    }

    public void decision(String blockedReason, String diagnostic) {
        registry.counter("watcher.shadow.decisions",
                "reason", reason(blockedReason), "diagnostic", reason(diagnostic)).increment();
    }

    public void providerCall(String provider, String status, Duration duration) {
        String safeProvider = provider(provider);
        String safeStatus = switch (status == null ? "" : status) {
            case "success", "rejected", "timeout", "error" -> status;
            default -> "other";
        };
        registry.counter("watcher.shadow.provider.calls",
                "provider", safeProvider, "status", safeStatus).increment();
        if (duration != null && !duration.isNegative()) {
            Timer.builder("watcher.shadow.provider.latency")
                    .tag("provider", safeProvider).tag("status", safeStatus)
                    .publishPercentileHistogram()
                    .register(registry).record(duration);
        }
    }

    /** Only bounded telemetry values: never tag untrusted model or chat identifiers. */
    public void providerFailure(String provider, ClassificationProviderException.Kind kind) {
        registry.counter("watcher.shadow.provider.failures", "provider", provider(provider),
                "reason", kind == null ? "other" : switch (kind) {
                    case OUTPUT_TRUNCATED -> "output_truncated";
                    case INVALID_RESPONSE, RESPONSE_TOO_LARGE -> "schema_invalid";
                    case TIMEOUT -> "timeout";
                    case TECHNICAL_FAILURE -> "technical";
                    case PRIVACY_ROUTE_UNAVAILABLE -> "privacy_route_unavailable";
                    case CONFIGURATION_ERROR -> "configuration_invalid";
                    case AUTHORIZATION_DENIED -> "authorization_denied";
                }).increment();
    }

    public void classificationLatency(Duration duration) {
        recordTimer("watcher.shadow.classification.latency", duration);
    }

    public void decisionLatency(Duration duration) {
        recordTimer("watcher.shadow.decision.latency", duration);
    }

    public void persistenceLatency(Duration duration) {
        recordTimer("watcher.shadow.persistence.latency", duration);
    }

    public void queueWait(Duration duration) {
        recordTimer("watcher.shadow.queue.wait", duration);
    }

    private void recordTimer(String name, Duration duration) {
        if (duration != null && !duration.isNegative())
            Timer.builder(name).publishPercentileHistogram().register(registry).record(duration);
    }

    /**
     * Extracts only numeric provider-reported usage. Cost is USD as reported
     * by OpenRouter; missing usage is not interpreted as zero external cost.
     * Parsing errors must never change the classification result.
     */
    public void reportedUsage(String provider, String responseBody) {
        String safeProvider = provider(provider);
        if (responseBody == null || responseBody.isBlank() || safeProvider.equals("other")) return;
        try {
            Map<?, ?> root = JSON.readValue(responseBody, Map.class);
            if (root == null) return;
            if ("openrouter".equals(safeProvider)) {
                if (root.get("choices") instanceof java.util.List<?> choices && !choices.isEmpty()) {
                    String finish = "missing";
                    for (Object item : choices) {
                        if (!(item instanceof Map<?, ?> choice)) continue;
                        String current = choice.get("finish_reason") instanceof String value ? switch (value) {
                            case "stop", "length", "max_tokens", "content_filter", "tool_calls", "end_turn" -> value;
                            default -> "other";
                        } : "missing";
                        if ("missing".equals(finish)) finish = current;
                        if ("length".equals(current) || "max_tokens".equals(current)) {
                            finish = current;
                            break;
                        }
                    }
                    registry.counter("watcher.shadow.provider.finish_reason",
                            "provider", safeProvider, "reason", finish).increment();
                    if ("length".equals(finish) || "max_tokens".equals(finish))
                        registry.counter("watcher.shadow.provider.truncated", "provider", safeProvider).increment();
                }
                if (!(root.get("usage") instanceof Map<?, ?> usage)) return;
                tokens(safeProvider, "input", usage.get("prompt_tokens"));
                tokens(safeProvider, "output", usage.get("completion_tokens"));
                if (usage.get("completion_tokens_details") instanceof Map<?, ?> details)
                    tokens(safeProvider, "reasoning", details.get("reasoning_tokens"));
                else if (usage.get("output_tokens") instanceof Map<?, ?> details)
                    tokens(safeProvider, "reasoning", details.get("reasoning"));
                cost(safeProvider, usage.get("cost"));
            } else if ("gemini".equals(safeProvider)) {
                if (!(root.get("usageMetadata") instanceof Map<?, ?> usage)) return;
                tokens(safeProvider, "input", usage.get("promptTokenCount"));
                tokens(safeProvider, "output", usage.get("candidatesTokenCount"));
            }
        } catch (RuntimeException ignored) {
            // Usage is optional telemetry: malformed metadata never blocks the watcher.
        }
    }

    private void tokens(String provider, String direction, Object value) {
        if (!(value instanceof Number number)) return;
        double count = number.doubleValue();
        if (!Double.isFinite(count) || count <= 0 || count > 1_000_000_000 || count != Math.floor(count)) return;
        registry.counter("watcher.shadow.provider.tokens",
                "provider", provider, "direction", direction).increment(count);
    }

    private void cost(String provider, Object value) {
        if (!(value instanceof Number number)) return;
        double usd = number.doubleValue();
        if (!Double.isFinite(usd) || usd <= 0 || usd > 1_000_000) return;
        registry.counter("watcher.shadow.provider.cost.usd", "provider", provider).increment(usd);
    }

    private static String provider(String value) {
        return "openrouter".equals(value) || "gemini".equals(value) ? value : "other";
    }

    private static String reason(String value) {
        if (value == null || value.isBlank()) return "none";
        return REASONS.contains(value) ? value : "other";
    }
}
