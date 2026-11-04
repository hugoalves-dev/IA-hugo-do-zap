package dev.hugo.watcher.ai;

import java.time.Instant;

public record ClassificationPromptSnapshot(int revision, String content, String contentHash, String origin,
                                           String description, Instant createdAt) {
    public String sha256() { return contentHash; }
    public String systemText() { return content + "\n\n" + ClassificationPromptService.IMMUTABLE_CORE; }
}
