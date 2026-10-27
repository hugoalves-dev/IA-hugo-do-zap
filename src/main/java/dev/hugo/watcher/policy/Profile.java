package dev.hugo.watcher.policy;

import java.util.Map;

public record Profile(String name, Map<PolicyField, Object> fields) {
    public Profile {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        fields = PolicyValues.copy(fields);
    }
}
