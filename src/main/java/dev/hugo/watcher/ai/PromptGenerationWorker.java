package dev.hugo.watcher.ai;

import dev.hugo.watcher.NotificationOutboxRepository;
import dev.hugo.watcher.telegram.TelegramAdminPersistenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Claims an authoring request in a committed transaction before making its single provider attempt. */
@Component
public class PromptGenerationWorker {
    private static final Logger log = LoggerFactory.getLogger(PromptGenerationWorker.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final PromptGenerationQueueRepository queue;
    private final PromptGenerationService generation;
    private final NotificationOutboxRepository outbox;
    private final TelegramAdminPersistenceRepository audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final PromptGenerationMetrics metrics;

    @Autowired
    public PromptGenerationWorker(PromptGenerationQueueRepository queue, PromptGenerationService generation,
                                  NotificationOutboxRepository outbox, TelegramAdminPersistenceRepository audit,
                                  PlatformTransactionManager manager, Clock clock, PromptGenerationMetrics metrics) {
        this.queue = queue;
        this.generation = generation;
        this.outbox = outbox;
        this.audit = audit;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
        this.metrics = metrics;
    }

    public PromptGenerationWorker(PromptGenerationQueueRepository queue, PromptGenerationService generation,
                                  NotificationOutboxRepository outbox, TelegramAdminPersistenceRepository audit,
                                  PlatformTransactionManager manager, Clock clock) {
        this(queue, generation, outbox, audit, manager, clock, null);
    }

    @Scheduled(fixedDelayString = "${watcher.telegram.prompt-generation-delay:PT2S}")
    public void process() {
        var cutoff = clock.instant().minus(Duration.ofMinutes(2));
        for (var stale : queue.staleDispatched(cutoff)) finish(stale, "UNKNOWN", cutoff, stale.diagnostic(), true);
        PromptGenerationJob job;
        try {
            job = queue.claimPending(clock.instant());
        } catch (RuntimeException failure) {
            if (metrics != null) metrics.stage("CLAIM", PromptGenerationFailure.ReasonCode.CLAIM_FAILURE.name());
            log.error("prompt_generation event=CLAIM_FAILED stage=CLAIM reason_code=CLAIM_FAILURE");
            return;
        }
        if (job == null) return;
        Instant claimedAt = clock.instant();
        Duration queueWait = Duration.between(job.createdAt(), claimedAt);
        if (metrics != null) metrics.claimed(job.operation(), queueWait);
        log.info("prompt_generation event=CLAIMED jobId={} operation={} queue_wait_ms={}",
                job.id(), job.operation(), Math.max(0, queueWait.toMillis()));
        PromptGenerationFailure failure = null;
        try {
            if ("REFINE".equals(job.operation())) generation.refine(job.draftId(), job.actorUserId(),
                    job.expectedDraftVersion(), job.instructions(), clock.instant(), job.providerContext());
            else generation.propose(job.draftId(), job.actorUserId(), job.expectedDraftVersion(),
                    job.instructions(), clock.instant(), job.providerContext());
        } catch (PromptGenerationFailure diagnostic) {
            failure = diagnostic;
        } catch (RuntimeException unexpected) {
            failure = new PromptGenerationFailure(PromptGenerationFailure.Stage.PROVIDER,
                    PromptGenerationFailure.ReasonCode.PROVIDER_FAILURE);
        }
        if (failure != null) log.warn(
                "prompt_generation event=GENERATION_FAILED jobId={} operation={} stage={} reason_code={}",
                job.id(), job.operation(), failure.stage().name(), failure.reasonCode().name());
        String terminalStatus = failure == null ? "COMPLETE"
                : failure.reasonCode() == PromptGenerationFailure.ReasonCode.PROVIDER_OUTCOME_UNKNOWN
                ? "UNKNOWN" : "FAILED";
        finish(job, terminalStatus, cutoff, failure, false);
    }

    private void finish(PromptGenerationJob job, String state, Instant cutoff, PromptGenerationFailure failure,
                        boolean staleRecovery) {
        PromptGenerationFailure resultFailure = failure != null ? failure : job.diagnostic();
        if ("UNKNOWN".equals(state) && resultFailure == null)
            resultFailure = new PromptGenerationFailure(PromptGenerationFailure.Stage.CLAIM,
                    PromptGenerationFailure.ReasonCode.WORKER_INTERRUPTED);
        String text = resultText(state, resultFailure);
        boolean updated;
        try {
            updated = Boolean.TRUE.equals(transactions.execute(status -> {
                boolean changed = "UNKNOWN".equals(state)
                        ? staleRecovery
                        ? queue.finishUnknown(job, clock.instant(), cutoff, failure)
                        : queue.finishUnknown(job, clock.instant(), failure)
                        : queue.finish(job.id(), state, clock.instant(), failure);
                if (!changed) return false;
                audit.audit("prompt_generation", "COMPLETE".equals(state) ? "PROPOSED" : "REJECTED",
                        job.actorUserId(), job.chatId(), clock.instant());
                String payload = JSON.writeValueAsString(Map.of("eventType", "telegram_admin_reply", "text", text));
                try {
                    outbox.enqueue("prompt-generation-result:" + job.id(), "telegram_admin_reply", payload,
                            "[]", clock.instant(), clock.instant().plus(Duration.ofMinutes(10)));
                } catch (RuntimeException outboxFailure) {
                    throw new PromptGenerationFailure(PromptGenerationFailure.Stage.OUTBOX,
                            PromptGenerationFailure.ReasonCode.RESULT_ENQUEUE_FAILED);
                }
                return true;
            }));
        } catch (PromptGenerationFailure outboxFailure) {
            recordResultEnqueueFailure(job, outboxFailure);
            return;
        } catch (RuntimeException persistenceFailure) {
            if (metrics != null) metrics.stage("PERSIST", PromptGenerationFailure.ReasonCode.PERSISTENCE_FAILURE.name());
            log.error("prompt_generation event=TERMINAL_PERSIST_FAILED jobId={} operation={} stage=PERSIST reason_code=PERSISTENCE_FAILURE failure_type={}",
                    job.id(), job.operation(), persistenceFailure.getClass().getSimpleName());
            return;
        }
        if (!updated) return;
        String stage = resultFailure == null ? null : resultFailure.stage().name();
        String reason = resultFailure == null ? null : resultFailure.reasonCode().name();
        Duration elapsed = Duration.between(job.createdAt(), clock.instant());
        if (metrics != null) metrics.terminal(job.operation(), state, stage, reason, elapsed);
        log.info("prompt_generation event=JOB_TERMINAL jobId={} operation={} status={} stage={} reason_code={} elapsed_ms={}",
                job.id(), job.operation(), state, stage == null ? "NONE" : stage,
                reason == null ? "NONE" : reason, Math.max(0, elapsed.toMillis()));
    }

    private void recordResultEnqueueFailure(PromptGenerationJob job, PromptGenerationFailure failure) {
        if (metrics != null) metrics.stage(failure.stage().name(), failure.reasonCode().name());
        try {
            queue.recordResultEnqueueFailure(job.id());
        } catch (RuntimeException persistenceFailure) {
            log.error("prompt_generation event=FAILURE_DIAGNOSTIC_PERSIST_FAILED jobId={} operation={} stage=OUTBOX reason_code=RESULT_ENQUEUE_FAILED",
                    job.id(), job.operation());
        }
        log.error("prompt_generation event=OUTBOX_ADMISSION_FAILED jobId={} operation={} stage=OUTBOX reason_code=RESULT_ENQUEUE_FAILED",
                job.id(), job.operation());
    }

    private static String resultText(String state, PromptGenerationFailure failure) {
        if ("COMPLETE".equals(state))
            return "Sua proposta de prompt está pronta como rascunho. Use /prompt_preview para revisar e editar antes de ativar.";
        if ("UNKNOWN".equals(state)) {
            if (failure != null && failure.reasonCode() == PromptGenerationFailure.ReasonCode.PROVIDER_OUTCOME_UNKNOWN)
                return "Não foi possível confirmar se o provedor processou a chamada. Confira o rascunho com /prompt_preview; a chamada não será repetida automaticamente.";
            if (failure != null && failure.reasonCode() == PromptGenerationFailure.ReasonCode.RESULT_ENQUEUE_FAILED)
                return "Não foi possível confirmar o envio do resultado. Confira o rascunho com /prompt_preview; a chamada de IA não será repetida automaticamente.";
            return "A solicitação de prompt terminou em estado incerto após interrupção. Confira o rascunho com /prompt_preview; ela não será repetida automaticamente.";
        }
        if (failure == null) return "Não foi possível gerar a proposta. O rascunho anterior foi preservado.";
        return switch (failure.reasonCode()) {
            case BUDGET_MODE_BLOCKED -> "A geração está bloqueada porque o orçamento local está desativado. Ative e configure o orçamento local antes de tentar novamente; o rascunho foi preservado.";
            case BUDGET_EXHAUSTED -> "O limite de orçamento local foi atingido. Aguarde a renovação ou revise o orçamento; o rascunho foi preservado.";
            case DRAFT_EXPIRED -> "O rascunho expirou. Inicie um novo rascunho para continuar; nenhum prompt foi ativado.";
            case DRAFT_CONFLICT -> "O rascunho foi alterado durante a geração. Revise a versão atual antes de continuar.";
            case DRAFT_UNAVAILABLE -> "O rascunho não está disponível. Inicie um novo rascunho para continuar.";
            case REFINEMENT_LIMIT -> "O limite de refinamentos foi atingido. Crie um novo rascunho para gerar outra proposta.";
            case SHADOW_PAUSED -> "A geração está pausada pelo estado atual da simulação ou classificação. O rascunho foi preservado.";
            case CONFIG_INVALID -> "A geração está indisponível por configuração de IA ou orçamento. Verifique a configuração local; o rascunho foi preservado.";
            case PROVIDER_FAILURE, INVALID_PROVIDER_OUTPUT -> "O provedor não concluiu a solicitação. O rascunho foi preservado e nenhuma nova chamada automática será feita.";
            case PROPOSAL_REJECTED -> "A proposta não passou na validação. O rascunho anterior foi preservado.";
            case INVALID_INSTRUCTIONS -> "Informe instruções explícitas válidas para gerar a proposta. O rascunho foi preservado.";
            default -> "Não foi possível gerar a proposta com segurança. O rascunho anterior foi preservado.";
        };
    }
}
