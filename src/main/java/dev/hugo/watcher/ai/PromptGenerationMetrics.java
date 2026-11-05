package dev.hugo.watcher.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/** Prompt-generation metrics with only finite labels; correlation identifiers belong in logs. */
@Component
public final class PromptGenerationMetrics {
    private static final Set<String> OPERATIONS = Set.of("GENERATE", "REFINE");
    private static final Set<String> JOB_STATUSES = Set.of("PENDING", "DISPATCHED", "COMPLETE", "FAILED", "UNKNOWN");
    private static final Set<String> DELIVERY_STATUSES = Set.of(
            "NOT_ENQUEUED", "PENDING", "PROCESSING", "ACKNOWLEDGED", "CANCELLED", "EXPIRED");
    private final MeterRegistry registry;

    public PromptGenerationMetrics(MeterRegistry registry) {
        this.registry = java.util.Objects.requireNonNull(registry);
    }

    public void enqueued(String operation) {
        jobs(operation, "PENDING", null);
    }

    public void claimed(String operation) {
        jobs(operation, "DISPATCHED", null);
    }

    public void claimed(String operation, Duration queueWait) {
        claimed(operation);
        Timer.builder("watcher.prompt.generation.queue.wait")
                .tag("operation", operation(operation))
                .publishPercentileHistogram()
                .register(registry)
                .record(duration(queueWait));
    }

    public void terminal(String operation, String status, String failureStage, String failureCode,
                         Duration elapsed) {
        String boundedStatus = status != null && JOB_STATUSES.contains(status) ? status : "OTHER";
        String boundedStage = stage(failureStage);
        String boundedCode = code(failureCode);
        jobs(operation, boundedStatus, boundedCode);
        if (!"NONE".equals(boundedStage)) stage(boundedStage, boundedCode);
        Timer.builder("watcher.prompt.generation.end_to_end")
                .tag("operation", operation(operation))
                .tag("status", boundedStatus)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration(elapsed));
    }

    public void provider(String operation, String outcome, Duration elapsed) {
        String boundedOutcome = "SUCCESS".equals(outcome) ? "SUCCESS" : code(outcome);
        Timer.builder("watcher.prompt.generation.provider.latency")
                .tag("operation", operation(operation))
                .tag("outcome", boundedOutcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration(elapsed));
    }

    public void stage(String stage, String reasonCode) {
        String boundedStage = stage(stage);
        String boundedCode = code(reasonCode);
        Counter.builder("watcher.prompt.generation.stage")
                .tag("stage", boundedStage)
                .tag("reason_code", boundedCode)
                .register(registry)
                .increment();
    }

    public void delivery(String status) {
        String boundedStatus = status != null && DELIVERY_STATUSES.contains(status) ? status : "OTHER";
        Counter.builder("watcher.prompt.generation.delivery")
                .tag("delivery_status", boundedStatus)
                .register(registry)
                .increment();
    }

    private void jobs(String operation, String status, String reasonCode) {
        Counter.builder("watcher.prompt.generation.jobs")
                .tag("operation", operation(operation))
                .tag("status", status)
                .tag("reason_code", reasonCode == null ? "NONE" : code(reasonCode))
                .register(registry)
                .increment();
    }

    private static String operation(String value) { return value != null && OPERATIONS.contains(value) ? value : "OTHER"; }

    private static String stage(String value) {
        if (value == null) return "NONE";
        for (PromptGenerationFailure.Stage stage : PromptGenerationFailure.Stage.values())
            if (stage.name().equals(value)) return value;
        return "OTHER";
    }

    private static String code(String value) {
        if (value == null) return "NONE";
        for (PromptGenerationFailure.ReasonCode code : PromptGenerationFailure.ReasonCode.values())
            if (code.name().equals(value)) return value;
        return "OTHER";
    }

    private static Duration duration(Duration value) {
        return value == null || value.isNegative() ? Duration.ZERO : value;
    }
}
