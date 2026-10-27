package dev.hugo.watcher.policy;

import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

@Service
public final class PolicyResolver {
    public EffectivePolicy resolve(GlobalPolicy global, Profile profile, ChatOverride chatOverride) {
        Objects.requireNonNull(global, "global");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(chatOverride, "chatOverride");
        EnumMap<PolicyField, ResolvedPolicyField> resolved = new EnumMap<>(PolicyField.class);
        apply(resolved, global.fields(), PolicySource.GLOBAL);
        apply(resolved, profile.fields(), PolicySource.PROFILE);
        apply(resolved, chatOverride.fields(), PolicySource.CHAT_OVERRIDE);
        return new EffectivePolicy(Map.copyOf(resolved), global.temporaryAvailability());
    }

    private void apply(Map<PolicyField, ResolvedPolicyField> target, Map<PolicyField, Object> fields, PolicySource source) {
        fields.forEach((field, value) -> target.put(field, new ResolvedPolicyField(value, source)));
    }
}
