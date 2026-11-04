package dev.hugo.watcher.ai;

import java.time.Instant;
import java.util.UUID;

public record PromptCalibrationRun(UUID id, int revision, long actorUserId, long chatId, Instant createdAt) { }
