package dev.hugo.watcher.policy;

import java.util.Map;

public record ChatOverride(Map<PolicyField, Object> fields, TemporaryAvailabilityOverride temporaryAvailability) {
    public ChatOverride { fields = PolicyValues.copy(fields); }
    public ChatOverride(Map<PolicyField, Object> fields) { this(fields, null); }
    public static ChatOverride empty() { return new ChatOverride(Map.of(), null); }
}
