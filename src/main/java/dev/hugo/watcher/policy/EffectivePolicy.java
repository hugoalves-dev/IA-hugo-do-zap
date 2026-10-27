package dev.hugo.watcher.policy;

import java.util.Map;

public record EffectivePolicy(Map<PolicyField, ResolvedPolicyField> fields,
                              TemporaryAvailabilityOverride temporaryAvailability,
                              DecisionPolicy decisionPolicy, Availability availability) {
    public EffectivePolicy { fields = Map.copyOf(fields); }

    public EffectivePolicy(Map<PolicyField, ResolvedPolicyField> fields,
                           TemporaryAvailabilityOverride temporaryAvailability, DecisionPolicy decisionPolicy) {
        this(fields, temporaryAvailability, decisionPolicy, null);
    }

    public EffectivePolicy(Map<PolicyField, ResolvedPolicyField> fields,
                           TemporaryAvailabilityOverride temporaryAvailability) {
        this(fields, temporaryAvailability, DecisionPolicy.disabled());
    }

    public Object value(PolicyField field) {
        ResolvedPolicyField resolved = fields.get(field);
        return resolved == null ? null : resolved.value();
    }

    public PolicySource origin(PolicyField field) {
        ResolvedPolicyField resolved = fields.get(field);
        return resolved == null ? null : resolved.source();
    }
}
