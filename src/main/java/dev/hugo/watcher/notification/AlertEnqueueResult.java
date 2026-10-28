package dev.hugo.watcher.notification;

import dev.hugo.watcher.OutboxAction;

public record AlertEnqueueResult(boolean accepted, String reason, OutboxAction action) {
    public static AlertEnqueueResult rejected(String reason) { return new AlertEnqueueResult(false, reason, null); }
    public static AlertEnqueueResult accepted(OutboxAction action) { return new AlertEnqueueResult(true, "QUEUED", action); }
}
