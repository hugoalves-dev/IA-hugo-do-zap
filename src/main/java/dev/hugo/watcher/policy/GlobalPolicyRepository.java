package dev.hugo.watcher.policy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Compatibility facade during the incremental migration in issue #24.
 * Spring-managed callers use Spring Data JDBC; the JDBC constructor remains for
 * existing explicit legacy construction sites and tests until their own migration.
 */
@Repository
public class GlobalPolicyRepository {
    private final JdbcTemplate jdbc;
    private final AvailabilityOverrideDataRepository data;

    @Autowired
    public GlobalPolicyRepository(AvailabilityOverrideDataRepository data) {
        this.data = Objects.requireNonNull(data, "data");
        this.jdbc = null;
    }

    public GlobalPolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.data = null;
    }

    public Optional<TemporaryAvailabilityOverride> temporaryAvailabilityOverride() {
        if (data != null) {
            return data.findById((short) 1).map(row -> new TemporaryAvailabilityOverride(
                    AvailabilityState.valueOf(row.state()),
                    row.startsAt().toInstant(), row.expiresAt().toInstant()));
        }
        List<TemporaryAvailabilityOverride> rows = jdbc.query("""
                select state, starts_at, expires_at
                  from availability_overrides
                 where singleton_id = 1
                """, (rs, row) -> new TemporaryAvailabilityOverride(
                AvailabilityState.valueOf(rs.getString("state")), rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant()));
        return rows.stream().findFirst();
    }

    public void saveTemporaryAvailabilityOverride(TemporaryAvailabilityOverride override) {
        Objects.requireNonNull(override, "override");
        if (data != null) {
            int affected = data.upsert(override.state().name(),
                    override.startsAt().atOffset(ZoneOffset.UTC),
                    override.expiresAt().atOffset(ZoneOffset.UTC));
            if (affected != 1) throw new IllegalStateException("availability override upsert affected " + affected + " rows");
            return;
        }
        jdbc.update("""
                insert into availability_overrides(singleton_id, state, starts_at, expires_at, updated_at)
                values (1, ?, ?, ?, now())
                on conflict (singleton_id) do update
                    set state=excluded.state, starts_at=excluded.starts_at, expires_at=excluded.expires_at, updated_at=now()
                """, override.state().name(), Timestamp.from(override.startsAt()), Timestamp.from(override.expiresAt()));
    }
}
