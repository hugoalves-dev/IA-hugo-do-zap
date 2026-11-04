package dev.hugo.watcher.ai;

import dev.hugo.watcher.ai.ClassificationPromptRepository.Draft;
import dev.hugo.watcher.ai.ClassificationPromptRepository.Revision;
import dev.hugo.watcher.ai.ClassificationPromptValidator.ValidationResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public final class ClassificationPromptService {
    public static final String DEFAULT_OPERATIONAL_PROMPT =
            "Decide notificationRecommended from whether the owner should be interrupted; ordinary requests may be relevant even at LOW urgency. "
            + "Decide replyRecommended only when a response is appropriate, and set notificationRecommended true whenever replyRecommended is true. "
            + "Urgency is an independent dimension measuring time pressure and consequences, not notification relevance or the word important alone. "
            + "LOW: explicitly not urgent or important without a deadline, such as when available. NORMAL: ordinary response needed without immediate consequences. "
            + "HIGH: needs action today or an imminent deadline with significant consequences. CRITICAL: immediate emergency with grave harm. "
            + "Do not infer a deadline or emergency from isolated words; if unclear use UNCERTAIN. "
            + "Consider whether the owner's contextual reply already resolved the request, whether a message is promotional, and whether a group trigger is verifiable from structured mention or reply-to evidence.";
    public static final Duration DRAFT_TTL = Duration.ofMinutes(10);
    public static final int MAX_PART_LENGTH = 4_096;
    public static final int MAX_REFINEMENTS = 3;
    private final ClassificationPromptRepository repository;
    private final ClassificationPromptValidator validator;
    private final TransactionTemplate transactions;

    public ClassificationPromptService(ClassificationPromptRepository repository, ClassificationPromptValidator validator,
                                       PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.validator = validator;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record PromptPreview(UUID draftId, Long baseRevision, String body, String diffSummary,
                                ValidationResult validation) {}
    public record HistoryEntry(Long revision, String contentHash, String origin, String description, long authorId,
                               Instant createdAt, String event, Long previousRevision, Long nextRevision) {}

    public ClassificationPromptSnapshot activeSnapshot() {
        return snapshot(repository.activeState().revisionId());
    }

    public ValidationResult validateDraft(String body) { return validator.validate(normalize(body)); }

    public Draft createManualDraft(long adminId, Long baseRevision, Instant now) {
        Objects.requireNonNull(now, "now");
        var active = activeSnapshot();
        Long activeId = pointer(active.revision());
        if (!Objects.equals(pointer(baseRevision), activeId)) throw conflict();
        var draft = new Draft(UUID.randomUUID(), adminId, activeId, normalize(active.content()), "manual", "",
                "editing", 0, 0, now.plus(DRAFT_TTL));
        repository.createDraft(draft);
        return draft;
    }

    public Draft appendManualPart(UUID draftId, long adminId, String part, Instant now) {
        Draft draft = editable(draftId, adminId, now);
        String normalized = normalize(part);
        requireBody(normalized, MAX_PART_LENGTH);
        requireBody(draft.body() + "\n" + normalized, ClassificationPromptValidator.MAX_BODY_LENGTH);
        if (!repository.appendDraftPart(draftId, adminId, draft.version(), normalized, now)) throw conflict();
        return editable(draftId, adminId, now);
    }

    public Draft saveProposal(UUID draftId, long adminId, String body, String origin, String description, Instant now) {
        Draft draft = editable(draftId, adminId, now);
        String normalized = normalize(body);
        requireBody(normalized, ClassificationPromptValidator.MAX_BODY_LENGTH);
        if (!List.of("manual", "ai_assisted").contains(origin) || description == null || description.length() > 500)
            throw new IllegalArgumentException("invalid_proposal_metadata");
        if (draft.refinementCount() >= MAX_REFINEMENTS
                || !repository.saveProposal(draftId, adminId, draft.version(), normalized, origin, description, now))
            throw conflict();
        return editable(draftId, adminId, now);
    }

    public PromptPreview preview(UUID draftId, long adminId, Instant now) {
        return transactions.execute(status -> {
            Draft draft = editable(draftId, adminId, now);
            if (!repository.setDraftStatus(draftId, adminId, draft.version(), "preview")) throw conflict();
            String body = normalize(draft.body());
            return new PromptPreview(draftId, draft.baseRevision(), body,
                    diff(snapshot(draft.baseRevision()).content(), body), validateDraft(body));
        });
    }

    public ClassificationPromptSnapshot activate(UUID draftId, long adminId, Long expectedActiveRevision, Instant now) {
        Objects.requireNonNull(now, "now");
        return transactions.execute(status -> {
            Draft draft = editable(draftId, adminId, now);
            String body = normalize(draft.body());
            ValidationResult validation = validateDraft(body);
            if (!validation.valid()) throw new IllegalArgumentException(String.join(",", validation.violationCodes()));
            Revision revision = repository.insertRevision(new Revision(0, body,
                    ClassificationPromptRepository.contentHash(body), draft.origin(), draft.description(), adminId, now));
            if (!repository.transition(pointer(expectedActiveRevision), revision.id(), "activate", adminId, now)) throw conflict();
            if (!repository.setDraftStatus(draftId, adminId, draft.version(), "activated")) throw conflict();
            return snapshot(revision);
        });
    }

    public ClassificationPromptSnapshot restore(long revisionId, long adminId, Long expectedActiveRevision, Instant now) {
        Objects.requireNonNull(now, "now");
        return transactions.execute(status -> {
            if (revisionId <= 0) throw new IllegalArgumentException("custom_revision_required");
            var restored = snapshot(repository.revision(revisionId).orElseThrow(ClassificationPromptService::conflict));
            if (!repository.transition(pointer(expectedActiveRevision), revisionId, "restore", adminId, now)) throw conflict();
            return restored;
        });
    }

    public ClassificationPromptSnapshot resetToDefault(long adminId, Long expectedActiveRevision, Instant now) {
        Objects.requireNonNull(now, "now");
        return transactions.execute(status -> {
            if (!repository.transition(pointer(expectedActiveRevision), null, "reset", adminId, now)) throw conflict();
            return defaultSnapshot();
        });
    }

    public boolean cancel(UUID draftId, long adminId, Instant now) {
        Objects.requireNonNull(now, "now");
        return Boolean.TRUE.equals(transactions.execute(status -> repository.draft(draftId, adminId, now)
                .filter(ClassificationPromptService::isEditable)
                .map(draft -> repository.setDraftStatus(draftId, adminId, draft.version(), "cancelled")).orElse(false)));
    }

    public List<HistoryEntry> history(int limit) {
        return repository.history(limit).stream().map(transition -> {
            var selected = snapshot(transition.nextRevision());
            return new HistoryEntry(transition.nextRevision(), selected.contentHash(), selected.origin(), selected.description(),
                    transition.authorId(), transition.changedAt(), transition.event(), transition.previousRevision(), transition.nextRevision());
        }).toList();
    }

    private Draft editable(UUID id, long owner, Instant now) {
        Objects.requireNonNull(now, "now");
        return repository.draft(id, owner, now).filter(ClassificationPromptService::isEditable)
                .orElseThrow(ClassificationPromptService::conflict);
    }
    private static boolean isEditable(Draft draft) {
        return draft.status().equals("editing") || draft.status().equals("preview");
    }
    private ClassificationPromptSnapshot snapshot(Long id) {
        return id == null || id == 0 ? defaultSnapshot()
                : snapshot(repository.revision(id).orElseThrow(ClassificationPromptService::conflict));
    }
    private static ClassificationPromptSnapshot snapshot(Revision revision) {
        return new ClassificationPromptSnapshot(revision.id(), revision.body(), revision.contentHash(), revision.origin(),
                revision.description(), revision.createdAt());
    }
    private static ClassificationPromptSnapshot defaultSnapshot() {
        return new ClassificationPromptSnapshot(0, DEFAULT_OPERATIONAL_PROMPT,
                ClassificationPromptRepository.contentHash(DEFAULT_OPERATIONAL_PROMPT), "java_default", "Java default operational prompt", Instant.EPOCH);
    }
    private static Long pointer(Long revision) { return revision != null && revision == 0 ? null : revision; }
    private static String normalize(String body) { return body == null ? null : body.replace("\r\n", "\n").replace('\r', '\n'); }
    private static void requireBody(String body, int maximum) {
        if (body == null || body.isBlank() || body.length() > maximum) throw new IllegalArgumentException("invalid_body_length");
    }
    private static IllegalStateException conflict() { return new IllegalStateException("prompt_state_conflict"); }
    private static String diff(String before, String after) {
        Map<String, Integer> counts = new HashMap<>();
        before.lines().forEach(line -> counts.merge(line, 1, Integer::sum));
        after.lines().forEach(line -> counts.merge(line, -1, Integer::sum));
        int removed = counts.values().stream().filter(value -> value > 0).mapToInt(Integer::intValue).sum();
        int added = -counts.values().stream().filter(value -> value < 0).mapToInt(Integer::intValue).sum();
        return "+" + added + " added lines, -" + removed + " removed lines";
    }
}
