package dev.hugo.watcher.policy;

import java.util.Map;

public record GlobalPolicy(Map<PolicyField, Object> fields, TemporaryAvailabilityOverride temporaryAvailability) {
    public GlobalPolicy { fields = PolicyValues.copy(fields); }
    public GlobalPolicy(Map<PolicyField, Object> fields) { this(fields, null); }
}
