package dev.hugo.watcher.ai;

import dev.hugo.watcher.simulation.Batch;

import java.util.Objects;

/** OpenRouter is primary; optional Gemini fallback runs only on technical failures. */
public final class ClassificationService {
    private final ClassificationProvider primary;
    private final ClassificationProvider fallback;
    private final ShadowMetrics metrics;

    public ClassificationService(ClassificationProvider primary, ClassificationProvider fallback) {
        this(primary, fallback, null);
    }

    public ClassificationService(ClassificationProvider primary, ClassificationProvider fallback, ShadowMetrics metrics) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.fallback = fallback;
        this.metrics = metrics;
    }

    public ClassificationResult classify(Batch batch) {
        return classify(ClassificationInput.fromBatch(batch));
    }

    public ClassificationResult classify(ClassificationInput input) {
        try {
            return primary.classify(input);
        } catch (ClassificationProviderException error) {
            if (metrics != null) metrics.providerFailure(primary.name(), error.kind());
            if (!error.isTechnicalFailure() || fallback == null || causedByBudget(error)) return ClassificationResult.unavailable(reason(error));
            try {
                return fallback.classify(input);
            } catch (RuntimeException failure) {
                if (metrics != null && failure instanceof ClassificationProviderException fallbackError)
                    metrics.providerFailure(fallback.name(), fallbackError.kind());
                return ClassificationResult.unavailable(reason(failure));
            }
        } catch (RuntimeException failure) {
            return ClassificationResult.unavailable(reason(failure));
        }
    }

    private static boolean causedByBudget(Throwable failure) {
        for (Throwable item = failure; item != null; item = item.getCause()) if (item instanceof BudgetExceededException) return true;
        return false;
    }
    private static String reason(Throwable failure) {
        if (causedByBudget(failure)) return "budget_exceeded";
        for (Throwable item = failure; item != null; item = item.getCause()) {
            if (item instanceof java.util.concurrent.TimeoutException || item instanceof java.net.http.HttpTimeoutException) return "provider_timeout";
            if (item instanceof ClassificationProviderException error) {
                switch (error.kind()) {
                    case AUTHORIZATION_DENIED: return "authorization_denied";
                    case PRIVACY_ROUTE_UNAVAILABLE: return "privacy_route_unavailable";
                    case CONFIGURATION_ERROR: return "configuration_invalid";
                    case TIMEOUT: return "provider_timeout";
                    case OUTPUT_TRUNCATED: return "provider_output_truncated";
                    case INVALID_RESPONSE, RESPONSE_TOO_LARGE: return "result_invalid";
                    default: break;
                }
            }
        }
        return "provider_unavailable";
    }
}
