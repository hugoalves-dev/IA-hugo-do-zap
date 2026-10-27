package dev.hugo.watcher.policy;

import java.util.Map;

public record ChatOverride(Map<PolicyField, Object> fields) {
    public ChatOverride { fields = PolicyValues.copy(fields); }
    public static ChatOverride empty() { return new ChatOverride(Map.of()); }
}
