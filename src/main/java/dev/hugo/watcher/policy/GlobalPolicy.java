package dev.hugo.watcher.policy;

import java.util.Map;

public record GlobalPolicy(Map<PolicyField, Object> fields) {
    public GlobalPolicy { fields = PolicyValues.copy(fields); }
}
