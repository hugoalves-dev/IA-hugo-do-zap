package dev.hugo.watcher.ai;

import dev.hugo.watcher.NotificationOutboxRepository;
import dev.hugo.watcher.telegram.TelegramAdminPersistenceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/** Claims a paid test durably, commits that claim, then calls the provider outside any SQL transaction. */
@Component
public class PromptCalibrationWorker {
    private static final Logger log = LoggerFactory.getLogger(PromptCalibrationWorker.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final PromptCalibrationQueueRepository queue;
    private final PromptCalibrationService calibration;
    private final NotificationOutboxRepository outbox;
    private final TelegramAdminPersistenceRepository audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PromptCalibrationWorker(PromptCalibrationQueueRepository queue, PromptCalibrationService calibration,
                                   NotificationOutboxRepository outbox, TelegramAdminPersistenceRepository audit,
                                   PlatformTransactionManager manager, Clock clock) {
        this.queue = queue;
        this.calibration = calibration;
        this.outbox = outbox;
        this.audit = audit;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${watcher.telegram.prompt-calibration-delay:PT2S}")
    public void process() {
        var stale = queue.staleDispatched(clock.instant().minus(Duration.ofMinutes(2)));
        for (var run : stale) enqueueResult(run, "Teste de prompt " + run.id().toString().substring(0,8)
                + " terminou em estado incerto após interrupção; não foi repetido automaticamente.", "UNKNOWN");
        var run = queue.claimPending(clock.instant());
        if (run == null) return;
        String result;
        String state;
        try {
            result = calibration.run(run.revision());
            state = "COMPLETE";
        } catch (RuntimeException failure) {
            // Provider exceptions may contain request/response data; log only the sanitized class name.
            log.warn("Prompt calibration failed; result will not be retried (failureType={})", failure.getClass().getSimpleName());
            result = "Teste de prompt v" + run.revision() + " falhou; resultado do provedor não será repetido automaticamente.";
            state = "FAILED";
        }
        enqueueResult(run, result, state);
    }

    private void enqueueResult(PromptCalibrationRun run, String result, String state) {
        transactions.executeWithoutResult(status -> {
            if ("UNKNOWN".equals(state)) {
                if (!queue.finishUnknown(run, "Resultado incerto após interrupção; não houve repetição automática.",
                        clock.instant(), clock.instant().minus(Duration.ofMinutes(2)))) return;
            } else if (!queue.finish(run.id(), state, result, clock.instant())) {
                return;
            }
            audit.audit("prompt_test", state, run.actorUserId(), run.chatId(), clock.instant());
            try {
                String payload = JSON.writeValueAsString(Map.of("eventType", "telegram_admin_reply", "text", result));
                outbox.enqueue("prompt-calibration-result:" + run.id(), "telegram_admin_reply", payload,
                        "[]", clock.instant(), clock.instant().plus(Duration.ofMinutes(10)));
            } catch (RuntimeException exception) {
                throw new IllegalStateException("could not queue prompt calibration result", exception);
            }
        });
    }
}
