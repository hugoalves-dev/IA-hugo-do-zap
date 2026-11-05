package dev.hugo.watcher.ai;

import java.time.Instant;
import java.util.UUID;

public record PromptGenerationJob(UUID id, UUID draftId, long expectedDraftVersion, long actorUserId,
                                 long chatId, String operation, String instructions, Instant createdAt,
                                 PromptGenerationFailure diagnostic) {
    public PromptGenerationContext providerContext() { return new PromptGenerationContext(id, operation); }
}
