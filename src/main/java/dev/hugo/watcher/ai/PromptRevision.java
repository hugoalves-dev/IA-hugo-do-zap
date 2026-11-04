package dev.hugo.watcher.ai;

import java.time.Instant;

public record PromptRevision(int revision, String body, String sha256, long authorUserId, Instant createdAt) { }
