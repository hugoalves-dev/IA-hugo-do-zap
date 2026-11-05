package dev.hugo.watcher.ai;

/** Safe, finite diagnostic for prompt authoring. Never carries an untrusted provider exception. */
public final class PromptGenerationFailure extends IllegalStateException {
    public enum Stage { CLAIM, PREFLIGHT, PROVIDER, VALIDATE, PERSIST, OUTBOX, DELIVERY }

    public enum ReasonCode {
        CONFIG_INVALID,
        BUDGET_MODE_BLOCKED,
        BUDGET_EXHAUSTED,
        SHADOW_PAUSED,
        DRAFT_UNAVAILABLE,
        DRAFT_EXPIRED,
        DRAFT_CONFLICT,
        REFINEMENT_LIMIT,
        INVALID_INSTRUCTIONS,
        PROVIDER_FAILURE,
        INVALID_PROVIDER_OUTPUT,
        PROPOSAL_REJECTED,
        PERSISTENCE_FAILURE,
        RESULT_ENQUEUE_FAILED,
        WORKER_INTERRUPTED,
        CLAIM_FAILURE
    }

    private final Stage stage;
    private final ReasonCode reasonCode;

    public PromptGenerationFailure(Stage stage, ReasonCode reasonCode) {
        super(java.util.Objects.requireNonNull(stage).name() + "/" + java.util.Objects.requireNonNull(reasonCode).name());
        this.stage = stage;
        this.reasonCode = reasonCode;
    }

    public Stage stage() { return stage; }
    public ReasonCode reasonCode() { return reasonCode; }
}
