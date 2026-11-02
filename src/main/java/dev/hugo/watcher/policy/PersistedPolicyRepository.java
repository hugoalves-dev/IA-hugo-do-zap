package dev.hugo.watcher.policy;

import org.springframework.beans.factory.annotation.Autowired;
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
    private final JdbcTemplate legacyJdbc;
    private final PolicyResolver resolver;
    private final GlobalPolicyRepository globalPolicies;
    private final GlobalPolicyDataRepository globals;
    private final PolicyProfileDataRepository profiles;
    private final ChatPolicyOverrideDataRepository overrides;

    /** Existing standalone tests may still use explicit JDBC construction. */
    public PersistedPolicyRepository(JdbcTemplate jdbc, PolicyResolver resolver) {
        this(jdbc, resolver, new GlobalPolicyRepository(jdbc));
    }

    public PersistedPolicyRepository(JdbcTemplate jdbc, PolicyResolver resolver, GlobalPolicyRepository globalPolicies) {
        this.legacyJdbc = jdbc;
        this.resolver = resolver;
        this.globalPolicies = globalPolicies;
        this.globals = null;
        this.profiles = null;
        this.overrides = null;
    }

    /** Spring-managed path: all routine policy reads use typed Spring Data JDBC repositories. */
    @Autowired
    public PersistedPolicyRepository(PolicyResolver resolver,
                                     GlobalPolicyRepository globalPolicies,
                                     GlobalPolicyDataRepository globals,
                                     PolicyProfileDataRepository profiles,
                                     ChatPolicyOverrideDataRepository overrides) {
        this.legacyJdbc = null;
        this.resolver = resolver;
        this.globalPolicies = globalPolicies;
        this.globals = globals;
        this.profiles = profiles;
        this.overrides = overrides;
    }

    public EffectivePolicy forChat(String chat, String fallbackProfile) {
        var globalFields = globals == null
                ? legacyJdbc.query("select fields::text from global_policy where singleton_id=1",
                        (rs, row) -> rs.getString(1))
                : globals.loadFields().stream().toList();
        if (globalFields.isEmpty()) throw new IllegalStateException("global policy missing");

        java.util.List<Override> explicit = overrides == null
                ? legacyJdbc.query("""
                        select distinct profile_name,fields::text from chat_policy_overrides
                         where chat_jid=? or chat_jid in (select alias from identity_aliases where canonical_identity=?)
                        """, (rs, row) -> new Override(rs.getString(1), rs.getString(2)), chat, chat)
                : overrides.forCanonicalChat(chat, chat).stream()
                        .map(row -> new Override(row.profile(), row.fields())).toList();

        // DISTINCT ignores chat_jid; conflicting policies across aliases must deny access.
        if (explicit.size() > 1) return new EffectivePolicy(Map.of(), null);
        String profile = explicit.isEmpty() ? fallbackProfile : explicit.getFirst().profile();
        var profileFields = profiles == null
                ? legacyJdbc.query("select fields::text from policy_profiles where profile_name=?",
                        (rs, row) -> rs.getString(1), profile)
                : profiles.loadFields(profile).stream().toList();
        if (profileFields.isEmpty()) throw new IllegalStateException("policy profile missing");

        return resolver.resolve(new GlobalPolicy(parse(globalFields.getFirst()),
                        globalPolicies.temporaryAvailabilityOverride().orElse(null)),
                new Profile(profile, parse(profileFields.getFirst())),
                explicit.isEmpty() ? ChatOverride.empty() : new ChatOverride(parse(explicit.getFirst().fields())));
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
