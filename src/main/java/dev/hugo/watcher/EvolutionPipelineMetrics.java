package dev.hugo.watcher;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded-cardinality operational tracing for the Evolution ingress pipeline.
 *
 * Message IDs never become metric labels. Debug correlation uses a truncated SHA-256
 * of a technical event ID, not raw JIDs, phones, names or message bodies.
 * An absent webhook is not classified as a failed delivery: this process cannot
 * observe whether Evolution received the upstream WhatsApp event.
 */
@Component
public final class EvolutionPipelineMetrics {
    private static final Logger log = LoggerFactory.getLogger(EvolutionPipelineMetrics.class);
    private final MeterRegistry registry;
    private final AtomicLong lastUpsertReceivedEpochSeconds = new AtomicLong();

    public enum Stage { WEBHOOK, RABBIT, INBOX, JOB, MESSAGE, TELEGRAM }
    public enum Outcome {
        RECEIVED, PUBLISHED, CONSUMED, ACCEPTED, DUPLICATE, IGNORED,
        QUARANTINED, REJECTED, COMPLETED, RETRY, DEAD_LETTER, FAILED,
        SENT, SUPPRESSED, UNKNOWN
    }

    public EvolutionPipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("watcher.evolution.last.upsert.received.epoch.seconds",
                        lastUpsertReceivedEpochSeconds, AtomicLong::get)
                .description("Unix timestamp of the latest authenticated MESSAGES_UPSERT webhook; 0 until first event")
                .register(registry);
    }

    public void record(Stage stage, Outcome outcome, String technicalEventId) {
        Counter.builder("watcher.evolution.pipeline.events")
                .description("Evolution event processing by fixed pipeline stage and outcome")
                .tag("stage", stage.name().toLowerCase(Locale.ROOT))
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .register(registry).increment();

        if (stage == Stage.WEBHOOK && outcome == Outcome.RECEIVED)
            lastUpsertReceivedEpochSeconds.set(Instant.now().getEpochSecond());

        if (log.isDebugEnabled()) {
            log.debug("evolution_pipeline stage={} outcome={} correlation={} at={}",
                    stage, outcome, opaqueCorrelation(technicalEventId), Instant.now());
        }
    }

    static String opaqueCorrelation(String technicalEventId) {
        if (technicalEventId == null || technicalEventId.isBlank()) return "unavailable";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(technicalEventId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
