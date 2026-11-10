package dev.hugo.watcher.notification;

import dev.hugo.watcher.WatcherProperties;
import dev.hugo.watcher.policy.AvailabilityService;
import dev.hugo.watcher.policy.AvailabilityState;
import dev.hugo.watcher.policy.PersistedPolicyRepository;
import dev.hugo.watcher.policy.PolicyField;
import dev.hugo.watcher.policy.ScheduleMode;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic ordering: chat policy override > exact canonical/contact alias rule > matched
 * marker > pre-existing name-regex fallback. For overlapping markers WORK_ONLY wins over ANYTIME,
 * and PRIORITY wins over NORMAL. Priority affects queue order, never AI urgency or authorization.
 */
@Service
public final class ContactNotificationRules {
    private final ContactNotificationRuleRepository repository;
    private final PersistedPolicyRepository policies;
    private final AvailabilityService availability;
    private final ZoneId timezone;

    public ContactNotificationRules(ContactNotificationRuleRepository repository,
                                    PersistedPolicyRepository policies,
                                    AvailabilityService availability, WatcherProperties watcher) {
        this.repository = repository;
        this.policies = policies;
        this.availability = availability;
        this.timezone = ZoneId.of(watcher.getTimezone());
    }

    public record Route(String profile, boolean allowed, boolean priority) { }

    public Route route(String canonicalJid, String displayName, boolean group, boolean legacyWorkOnly,
                       boolean inLegacyWindow, Instant now) {
        String fallback = legacyWorkOnly ? "WORK_ONLY" : "ANYTIME";
        Route unchanged = new Route(fallback, !legacyWorkOnly || inLegacyWindow, false);
        if (canonicalJid == null || canonicalJid.isBlank()) return unchanged;
        List<ContactNotificationRuleRepository.Rule> contacts = new ArrayList<>();
        List<ContactNotificationRuleRepository.Rule> markers = new ArrayList<>();
        Set<String> tokens = tokens(displayName);
        for (var rule : repository.forChat(canonicalJid)) {
            if ("CONTACT".equals(rule.selectorType())) contacts.add(rule);
            else if ("MARKER".equals(rule.selectorType()) && tokens.contains(rule.selector())) markers.add(rule);
        }
        if (contacts.isEmpty() && markers.isEmpty()
                && (!group || !policies.hasExplicitChatOverride(canonicalJid))) return unchanged;

        String markerProfile = profile(markers, fallback);
        String selectedProfile = profile(contacts, markerProfile);
        boolean priority = !contacts.isEmpty() ? prioritized(contacts) : prioritized(markers);
        // Explicit chat/profile overrides are resolved by the existing policy service.
        var effective = policies.forChat(canonicalJid, selectedProfile);
        var mode = effective.value(PolicyField.MODE);
        if (!(mode instanceof ScheduleMode schedule)) return new Route("WORK_ONLY", false, priority);
        var available = availability.at(now, timezone, effective);
        return new Route(schedule == ScheduleMode.WORK_WINDOW ? "WORK_ONLY" : "ANYTIME",
                available.state() == AvailabilityState.AVAILABLE, priority);
    }

    private static String profile(List<ContactNotificationRuleRepository.Rule> matches, String fallback) {
        if (matches.stream().anyMatch(rule -> "WORK_ONLY".equals(rule.profile()))) return "WORK_ONLY";
        if (matches.stream().anyMatch(rule -> "ANYTIME".equals(rule.profile()))) return "ANYTIME";
        return fallback;
    }

    private static boolean prioritized(List<ContactNotificationRuleRepository.Rule> matches) {
        return matches.stream().anyMatch(rule -> "PRIORITY".equals(rule.priority()));
    }

    /** Normalize accents/case, and match whole tokens only: CH cannot match CHAMADA. */
    static Set<String> tokens(String name) {
        if (name == null || name.isBlank()) return Set.of();
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toUpperCase(Locale.ROOT);
        return java.util.Arrays.stream(normalized.split("[^A-Z0-9]+"))
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static String marker(String input) {
        if (input == null) throw new IllegalArgumentException("Marcador inválido.");
        String result = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "")
                .toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z0-9]{2,32}")) throw new IllegalArgumentException("Marcador deve ter 2 a 32 letras/números, sem espaços.");
        return result;
    }
}
