package dev.hugo.watcher.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

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
    record Revision(long id, String body, String contentHash, String origin, String description, long authorId, Instant createdAt) {}
    record Draft(UUID id, long ownerId, Long baseRevision, String body, String origin, String description, String status, int refinementCount, long version, Instant expiresAt) {}
    record ActiveState(Long revisionId) {}
    record Transition(Long previousRevision, Long nextRevision, String event, long authorId, Instant changedAt) {}
    default void createDraft(Draft draft) { throw new UnsupportedOperationException(); }
    default Optional<Draft> draft(UUID id, long ownerId, Instant now) { throw new UnsupportedOperationException(); }
    default boolean appendDraftPart(UUID id, long ownerId, long version, String part, Instant now) { throw new UnsupportedOperationException(); }
    default boolean saveProposal(UUID id, long ownerId, long version, String body, String origin, String description, Instant now) { throw new UnsupportedOperationException(); }
    default boolean setDraftStatus(UUID id, long ownerId, long version, String status) { throw new UnsupportedOperationException(); }
    default Revision insertRevision(Revision revision) { throw new UnsupportedOperationException(); }
    default Optional<Revision> revision(long id) { return find(Math.toIntExact(id)).map(r -> new Revision(r.revision(), r.body(), r.sha256(), "manual", "", r.authorUserId(), r.createdAt())); }
    default ActiveState activeState() { throw new UnsupportedOperationException(); }
    default List<Transition> history(int limit) { throw new UnsupportedOperationException(); }
    default boolean transition(Long expected, Long next, String event, long authorId, Instant now) { throw new UnsupportedOperationException(); }
    static String contentHash(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
