package dev.hugo.watcher.ai;

import dev.hugo.watcher.OperationalState;
import dev.hugo.watcher.ai.ClassificationPromptRepository.Draft;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.Duration;
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
    private final PromptGenerationMetrics metrics;

    @Autowired
    public PromptGenerationService(ClassificationPromptService prompts, ClassificationPromptRepository repository,
                                   ClassificationPromptValidator validator, OpenRouterPromptGenerator generator,
                                   AiClassificationProperties properties, OperationalState operational,
                                   PlatformTransactionManager transactionManager, PromptGenerationMetrics metrics) {
        this(prompts, repository, validator, generator, properties, operational, metrics);
    }

    public PromptGenerationService(ClassificationPromptService prompts, ClassificationPromptRepository repository,
                                   ClassificationPromptValidator validator, OpenRouterPromptGenerator generator,
                                   AiClassificationProperties properties, OperationalState operational,
                                   PlatformTransactionManager transactionManager) {
        this(prompts, repository, validator, generator, properties, operational, (PromptGenerationMetrics) null);
    }

    PromptGenerationService(ClassificationPromptService prompts, ClassificationPromptRepository repository,
                            ClassificationPromptValidator validator, OpenRouterPromptGenerator generator,
                            AiClassificationProperties properties, OperationalState operational,
                            PromptGenerationMetrics metrics) {
        this.prompts = prompts;
        this.repository = repository;
        this.validator = validator;
        this.generator = generator;
        this.properties = properties;
        this.operational = operational;
        this.metrics = metrics;
    }

    PromptGenerationService(ClassificationPromptService prompts, ClassificationPromptRepository repository,
                            ClassificationPromptValidator validator, OpenRouterPromptGenerator generator,
                            AiClassificationProperties properties, OperationalState operational) {
        this(prompts, repository, validator, generator, properties, operational, (PromptGenerationMetrics) null);
    }

    public Proposal propose(UUID draftId, long adminId, String explicitInstructions, Instant now) {
        return run(draftId, adminId, -1, explicitInstructions, now, false, null);
    }

    public Proposal propose(UUID draftId, long adminId, long expectedVersion, String explicitInstructions, Instant now) {
        return run(draftId, adminId, expectedVersion, explicitInstructions, now, false, null);
    }

    public Proposal propose(UUID draftId, long adminId, long expectedVersion, String explicitInstructions,
                            Instant now, PromptGenerationContext context) {
        return run(draftId, adminId, expectedVersion, explicitInstructions, now, false, context);
    }

    public Proposal refine(UUID draftId, long adminId, String explicitInstructions, Instant now) {
        return run(draftId, adminId, -1, explicitInstructions, now, true, null);
    }

    public Proposal refine(UUID draftId, long adminId, long expectedVersion, String explicitInstructions, Instant now) {
        return run(draftId, adminId, expectedVersion, explicitInstructions, now, true, null);
    }

    public Proposal refine(UUID draftId, long adminId, long expectedVersion, String explicitInstructions,
                           Instant now, PromptGenerationContext context) {
        return run(draftId, adminId, expectedVersion, explicitInstructions, now, true, context);
    }

    private Proposal run(UUID draftId, long adminId, long expectedVersion, String instructions, Instant now,
                         boolean refinement, PromptGenerationContext context) {
        Draft draft = repository.draftForGeneration(draftId, adminId).orElseThrow(() -> failure(
                PromptGenerationFailure.Stage.PREFLIGHT, PromptGenerationFailure.ReasonCode.DRAFT_UNAVAILABLE));
        if (!draft.expiresAt().isAfter(now)) throw failure(
                PromptGenerationFailure.Stage.PREFLIGHT, PromptGenerationFailure.ReasonCode.DRAFT_EXPIRED);
        if (expectedVersion >= 0 && draft.version() != expectedVersion) throw failure(
                PromptGenerationFailure.Stage.PREFLIGHT, PromptGenerationFailure.ReasonCode.DRAFT_CONFLICT);
        if (draft.origin().equals("ai_assisted") && draft.refinementCount() >= ClassificationPromptService.MAX_REFINEMENTS)
            throw failure(PromptGenerationFailure.Stage.PREFLIGHT, PromptGenerationFailure.ReasonCode.REFINEMENT_LIMIT);
        if (instructions == null || instructions.isBlank() || instructions.length() > 4096)
            throw failure(PromptGenerationFailure.Stage.PREFLIGHT, PromptGenerationFailure.ReasonCode.INVALID_INSTRUCTIONS);
        PromptGenerationFailure denial = ListGate.failure(operational, properties);
        if (denial != null) throw denial;

        OpenRouterPromptGenerator.Proposal output;
        String operation = context == null ? (refinement ? "REFINE" : "GENERATE") : context.operation();
        long providerStarted = System.nanoTime();
        String providerOutcome = "SUCCESS";
        try {
            output = generator.generate(draft.body(), instructions,
                    () -> ListGate.failure(operational, properties) == null, context);
        } catch (PromptGenerationFailure failure) {
            providerOutcome = failure.reasonCode().name();
            throw failure;
        } catch (BudgetExceededException failure) {
            providerOutcome = PromptGenerationFailure.ReasonCode.BUDGET_EXHAUSTED.name();
            throw failure(PromptGenerationFailure.Stage.PROVIDER, PromptGenerationFailure.ReasonCode.BUDGET_EXHAUSTED);
        } catch (RuntimeException failure) {
            providerOutcome = PromptGenerationFailure.ReasonCode.PROVIDER_FAILURE.name();
            throw failure(PromptGenerationFailure.Stage.PROVIDER, PromptGenerationFailure.ReasonCode.PROVIDER_FAILURE);
        } finally {
            if (metrics != null) metrics.provider(operation, providerOutcome,
                    Duration.ofNanos(System.nanoTime() - providerStarted));
        }
        String body = normalize(output.prompt());
        String summary = output.summary().strip();
        if (summary.length() > 500) summary = summary.substring(0, 500);
        if (!validator.validate(body).valid()) throw failure(
                PromptGenerationFailure.Stage.VALIDATE, PromptGenerationFailure.ReasonCode.PROPOSAL_REJECTED);
        Draft saved;
        try {
            saved = prompts.saveProposal(draftId, adminId, draft.version(), body, "ai_assisted", summary, now);
        } catch (IllegalStateException conflict) {
            throw failure(PromptGenerationFailure.Stage.PERSIST, PromptGenerationFailure.ReasonCode.DRAFT_CONFLICT);
        } catch (RuntimeException persistenceFailure) {
            throw failure(PromptGenerationFailure.Stage.PERSIST, PromptGenerationFailure.ReasonCode.PERSISTENCE_FAILURE);
        }
        return new Proposal(saved, summary);
    }

    private static PromptGenerationFailure failure(PromptGenerationFailure.Stage stage,
                                                   PromptGenerationFailure.ReasonCode reason) {
        return new PromptGenerationFailure(stage, reason);
    }

    private static String normalize(String body) { return body == null ? null : body.replace("\r\n", "\n").replace('\r', '\n'); }

    private static final class ListGate {
        static PromptGenerationFailure failure(OperationalState operational, AiClassificationProperties properties) {
            if (operational == null || !operational.simulation() || !operational.classification())
                return PromptGenerationService.failure(PromptGenerationFailure.Stage.PREFLIGHT,
                        PromptGenerationFailure.ReasonCode.SHADOW_PAUSED);
            if (properties == null) return PromptGenerationService.failure(PromptGenerationFailure.Stage.PREFLIGHT,
                    PromptGenerationFailure.ReasonCode.CONFIG_INVALID);
            if (properties.disableLocalBudget()) return PromptGenerationService.failure(
                    PromptGenerationFailure.Stage.PREFLIGHT, PromptGenerationFailure.ReasonCode.BUDGET_MODE_BLOCKED);
            if (!properties.configured() || properties.attemptHoldMicros() <= 0)
                return PromptGenerationService.failure(PromptGenerationFailure.Stage.PREFLIGHT,
                        PromptGenerationFailure.ReasonCode.CONFIG_INVALID);
            return null;
        }
    }

    public record Proposal(Draft draft, String changeSummary) { }
}
