package dev.hugo.watcher.ai;

import dev.hugo.watcher.simulation.Batch;

import java.util.Objects;

/** Gemini is primary. OpenRouter is used only after a technical Gemini failure. */
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
            if (!error.isTechnicalFailure() || fallback == null) return ClassificationResult.uncertain();
            try {
                return fallback.classify(input);
            } catch (RuntimeException ignored) {
                return ClassificationResult.uncertain();
            }
        } catch (RuntimeException ignored) {
            return ClassificationResult.uncertain();
        }
    }
}
