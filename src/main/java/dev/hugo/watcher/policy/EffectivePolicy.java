package dev.hugo.watcher.policy;

import java.util.Map;

public record EffectivePolicy(Map<PolicyField, ResolvedPolicyField> fields,
                              TemporaryAvailabilityOverride temporaryAvailability) {
    public EffectivePolicy { fields = Map.copyOf(fields); }

    public Object value(PolicyField field) {
        ResolvedPolicyField resolved = fields.get(field);
        return resolved == null ? null : resolved.value();
    }

    public PolicySource origin(PolicyField field) {
        ResolvedPolicyField resolved = fields.get(field);
        return resolved == null ? null : resolved.source();
    }
}
