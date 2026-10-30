package dev.hugo.watcher.ai;

import dev.hugo.watcher.simulation.Batch;

import java.util.Objects;

/** OpenRouter is primary; optional Gemini fallback runs only on technical failures. */
public final class ClassificationService {
    private final ClassificationProvider primary;
    private final ClassificationProvider fallback;

    public ClassificationService(ClassificationProvider primary, ClassificationProvider fallback) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.fallback = fallback;
    }

    public ClassificationResult classify(Batch batch) {
        return classify(ClassificationInput.fromBatch(batch));
    }

    public ClassificationResult classify(ClassificationInput input) {
        try {
            return primary.classify(input);
        } catch (ClassificationProviderException error) {
            if (!error.isTechnicalFailure() || fallback == null) return ClassificationResult.unavailable();
            try {
                return fallback.classify(input);
            } catch (RuntimeException ignored) {
                return ClassificationResult.unavailable();
            }
        } catch (RuntimeException ignored) {
            return ClassificationResult.unavailable();
        }
    }
}
