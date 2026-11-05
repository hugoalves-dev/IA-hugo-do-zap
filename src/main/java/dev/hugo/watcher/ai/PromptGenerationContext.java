package dev.hugo.watcher.ai;

import java.util.UUID;

/** Bounded correlation data for provider-attempt logs; never a metric label. */
public record PromptGenerationContext(UUID jobId, String operation) {
    public PromptGenerationContext {
        java.util.Objects.requireNonNull(jobId, "jobId");
        if (!"GENERATE".equals(operation) && !"REFINE".equals(operation))
            throw new IllegalArgumentException("invalid prompt-generation operation");
    }
}
