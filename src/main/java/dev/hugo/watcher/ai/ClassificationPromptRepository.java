package dev.hugo.watcher.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ClassificationPromptRepository {
    Optional<PromptRevision> active();
    Optional<PromptRevision> find(int revision);
    List<PromptRevision> latest(int limit);
    PromptRevision createDraft(String body, String hash, long actor, Instant at);
    long activate(int revision, long expectedVersion, long actor, Instant at);
    long activeVersion();
    boolean hasPendingInput(long userId, long chatId, Instant now);
    void beginInput(long userId, long chatId, Instant expiresAt, Instant now);
    PromptRevision createDraftFromInput(long userId, long chatId, String body, String hash, Instant at);
}
