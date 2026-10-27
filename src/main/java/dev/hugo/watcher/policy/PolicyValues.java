package dev.hugo.watcher.policy;

import java.util.EnumMap;
import java.util.Map;

final class PolicyValues {
    private PolicyValues() {}

    static Map<PolicyField, Object> copy(Map<PolicyField, Object> fields) {
        EnumMap<PolicyField, Object> copy = new EnumMap<>(PolicyField.class);
        fields.forEach((field, value) -> copy.put(field, field.copyAndValidate(value)));
        return Map.copyOf(copy);
    }
}
