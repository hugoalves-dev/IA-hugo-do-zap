package dev.hugo.watcher.ai;

import java.time.Instant;

/** One immutable prompt selection captured for the duration of classification. */
public record ClassificationPromptSnapshot(long revision, String content, String contentHash,
                                           String origin, String description, Instant createdAt) {}
