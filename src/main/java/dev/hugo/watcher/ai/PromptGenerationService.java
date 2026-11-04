package dev.hugo.watcher.ai;

import dev.hugo.watcher.OperationalState;
import dev.hugo.watcher.ai.ClassificationPromptRepository.Draft;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;

/** Creates validated editable prompt proposals; never activates a revision. */
@Service
public final class PromptGenerationService {
    private final ClassificationPromptService prompts;
    private final ClassificationPromptRepository repository;
    private final ClassificationPromptValidator validator;
    private final OpenRouterPromptGenerator generator;
    private final AiClassificationProperties properties;
    private final OperationalState operational;

    public PromptGenerationService(ClassificationPromptService prompts, ClassificationPromptRepository repository,
                                   ClassificationPromptValidator validator, OpenRouterPromptGenerator generator,
                                   AiClassificationProperties properties, OperationalState operational) {
        this.prompts = prompts;
        this.repository = repository;
        this.validator = validator;
        this.generator = generator;
        this.properties = properties;
        this.operational = operational;
    }

    public Proposal propose(UUID draftId, long adminId, String explicitInstructions, Instant now) {
        return run(draftId, adminId, explicitInstructions, now, false);
    }

    public Proposal refine(UUID draftId, long adminId, String explicitInstructions, Instant now) {
        return run(draftId, adminId, explicitInstructions, now, true);
    }

    private Proposal run(UUID draftId, long adminId, String instructions, Instant now, boolean refinement) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("prompt_generation_must_run_outside_transaction");
        Draft draft = repository.draft(draftId, adminId, now).orElseThrow(() -> new IllegalStateException("prompt_draft_unavailable"));
        if (refinement && draft.refinementCount() >= ClassificationPromptService.MAX_REFINEMENTS)
            throw new IllegalStateException("prompt_refinement_limit");
        if (!ListGate.allowed(operational, properties)) throw new IllegalStateException("prompt_generation_unavailable");
        OpenRouterPromptGenerator.Proposal output = generator.generate(draft.body(), instructions,
                () -> ListGate.allowed(operational, properties));
        String body = normalize(output.prompt());
        String summary = output.summary().strip();
        if (summary.length() > 500) summary = summary.substring(0, 500);
        if (!validator.validate(body).valid()) throw new IllegalArgumentException("prompt_proposal_rejected");
        Draft saved = prompts.saveProposal(draftId, adminId, draft.version(), body, "ai_assisted", summary, now);
        return new Proposal(saved, summary);
    }

    private static String normalize(String body) { return body == null ? null : body.replace("\r\n", "\n").replace('\r', '\n'); }

    private static final class ListGate {
        static boolean allowed(OperationalState operational, AiClassificationProperties properties) {
            return operational != null && operational.simulation() && operational.classification()
                    && properties != null && properties.configured() && !properties.disableLocalBudget()
                    && properties.attemptHoldMicros() > 0;
        }
    }

    public record Proposal(Draft draft, String changeSummary) { }
}
