package dev.hugo.watcher.ai;

import dev.hugo.watcher.NotificationOutboxRepository;
import dev.hugo.watcher.telegram.TelegramAdminPersistenceRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/** Claims an authoring request in a committed transaction before making its single provider attempt. */
@Component
public class PromptGenerationWorker {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final PromptGenerationQueueRepository queue;
    private final PromptGenerationService generation;
    private final NotificationOutboxRepository outbox;
    private final TelegramAdminPersistenceRepository audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PromptGenerationWorker(PromptGenerationQueueRepository queue, PromptGenerationService generation,
                                  NotificationOutboxRepository outbox, TelegramAdminPersistenceRepository audit,
                                  PlatformTransactionManager manager, Clock clock) {
        this.queue = queue;
        this.generation = generation;
        this.outbox = outbox;
        this.audit = audit;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${watcher.telegram.prompt-generation-delay:PT2S}")
    public void process() {
        var cutoff = clock.instant().minus(Duration.ofMinutes(2));
        for (var stale : queue.staleDispatched(cutoff)) finish(stale, "UNKNOWN", cutoff);
        var job = queue.claimPending(clock.instant());
        if (job == null) return;
        String state = "COMPLETE";
        try {
            if ("REFINE".equals(job.operation())) generation.refine(job.draftId(), job.actorUserId(),
                    job.expectedDraftVersion(), job.instructions(), clock.instant());
            else generation.propose(job.draftId(), job.actorUserId(), job.expectedDraftVersion(),
                    job.instructions(), clock.instant());
        } catch (RuntimeException ignored) {
            // Provider errors and prompt content are intentionally neither returned nor logged.
            state = "FAILED";
        }
        finish(job, state, cutoff);
    }

    private void finish(PromptGenerationJob job, String state, java.time.Instant cutoff) {
        String text = switch (state) {
            case "COMPLETE" -> "Sua proposta de prompt está pronta como rascunho. Use /prompt_preview para revisar e editar antes de ativar.";
            case "UNKNOWN" -> "A solicitação de prompt terminou em estado incerto após interrupção. Ela não será repetida automaticamente.";
            default -> "Não foi possível gerar a proposta. O rascunho anterior foi preservado.";
        };
        transactions.executeWithoutResult(status -> {
            boolean updated = "UNKNOWN".equals(state)
                    ? queue.finishUnknown(job, clock.instant(), cutoff)
                    : queue.finish(job.id(), state, clock.instant());
            if (!updated) return;
            audit.audit("prompt_generation", "COMPLETE".equals(state) ? "APPLIED" : "REJECTED",
                    job.actorUserId(), job.chatId(), clock.instant());
            try {
                String payload = JSON.writeValueAsString(Map.of("eventType", "telegram_admin_reply", "text", text));
                outbox.enqueue("prompt-generation-result:" + job.id(), "telegram_admin_reply", payload,
                        "[]", clock.instant(), clock.instant().plus(Duration.ofMinutes(10)));
            } catch (RuntimeException exception) {
                throw new IllegalStateException("could not queue prompt generation result");
            }
        });
    }
}
