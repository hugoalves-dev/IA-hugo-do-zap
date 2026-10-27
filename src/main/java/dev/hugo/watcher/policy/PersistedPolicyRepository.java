package dev.hugo.watcher.policy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Map;

/** Loads only persisted policy values. Missing holiday attestations never become holidays. */
@Repository
public class PersistedPolicyRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final PolicyResolver resolver;
    public PersistedPolicyRepository(JdbcTemplate jdbc, PolicyResolver resolver) {
        this.jdbc = jdbc;
        this.resolver = resolver;
    }
    public EffectivePolicy forChat(String chat, String fallbackProfile) {
        var globals = jdbc.query("select fields::text from global_policy where singleton_id=1", (rs, row) -> rs.getString(1));
        if (globals.isEmpty()) throw new IllegalStateException("global policy missing");
        var overrides = jdbc.query("""
                select distinct profile_name,fields::text from chat_policy_overrides
                 where chat_jid=? or chat_jid in (select alias from identity_aliases where canonical_identity=?)
                """, (rs, row) -> new Override(rs.getString(1), rs.getString(2)), chat, chat);
        // Conflicting explicit policies survive an alias merge; do not silently choose one or widen permissions.
        if (overrides.size() > 1) return new EffectivePolicy(Map.of(), null);
        String profile = overrides.isEmpty() ? fallbackProfile : overrides.getFirst().profile();
        var profiles = jdbc.query("select fields::text from policy_profiles where profile_name=?", (rs, row) -> rs.getString(1), profile);
        if (profiles.isEmpty()) throw new IllegalStateException("policy profile missing");
        return resolver.resolve(new GlobalPolicy(parse(globals.getFirst()),
                new GlobalPolicyRepository(jdbc).temporaryAvailabilityOverride().orElse(null)),
                new Profile(profile, parse(profiles.getFirst())),
                overrides.isEmpty() ? ChatOverride.empty() : new ChatOverride(parse(overrides.getFirst().fields())));
    }
    private static Map<PolicyField,Object> parse(String raw) {
        JsonNode fields = JSON.readTree(raw);
        if (!fields.isObject()) throw new IllegalArgumentException("policy fields must be an object");
        Map<PolicyField,Object> values = new EnumMap<>(PolicyField.class);
        if (fields.has("mode")) values.put(PolicyField.MODE, ScheduleMode.valueOf(fields.get("mode").asText()));
        if (fields.has("includeOptionalHolidays")) {
            if (!fields.get("includeOptionalHolidays").isBoolean()) throw new IllegalArgumentException("invalid optional holiday policy");
            values.put(PolicyField.INCLUDE_OPTIONAL_HOLIDAYS, fields.get("includeOptionalHolidays").asBoolean());
        }
        if (fields.has("workDays")) {
            if (!fields.get("workDays").isArray()) throw new IllegalArgumentException("invalid work days");
            var days = new HashSet<DayOfWeek>();
            for (JsonNode day : fields.get("workDays")) days.add(DayOfWeek.valueOf(day.asText()));
            values.put(PolicyField.WORK_DAYS, days);
        }
        if (fields.has("windows")) {
            if (!fields.get("windows").isArray()) throw new IllegalArgumentException("invalid work windows");
            var windows = new ArrayList<WorkWindow>();
            for (JsonNode window : fields.get("windows")) windows.add(new WorkWindow(
                    LocalTime.parse(window.path("start").asText()), LocalTime.parse(window.path("end").asText())));
            values.put(PolicyField.WINDOWS, windows);
        }
        return values;
    }
    private record Override(String profile, String fields) { }
}
