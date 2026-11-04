package dev.hugo.watcher.telegram;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class JdbcRuntimeSettingsRepository implements RuntimeSettingsRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcRuntimeSettingsRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override public Map<String, RuntimeSettingRow> findAll() {
        Map<String, RuntimeSettingRow> result = new LinkedHashMap<>();
        jdbc.query("select setting_key,value_type,value_json::text,is_overridden,version,updated_by,updated_at from telegram_runtime_settings",
                (ResultSetExtractor<Void>) rs -> {
                    while (rs.next()) result.put(rs.getString("setting_key"), new RuntimeSettingRow(rs.getString("setting_key"),
                            rs.getString("value_type"), rs.getString("value_json"), rs.getBoolean("is_overridden"),
                            rs.getLong("version"), rs.getLong("updated_by"), rs.getTimestamp("updated_at").toInstant()));
                    return null;
                });
        return Map.copyOf(result);
    }

    @Override public RuntimeSettingRow update(String key, String type, String valueJson, boolean overridden,
                                              long expectedVersion, long actorId, Instant at) {
        return transactions.execute(status -> {
            RuntimeSettingRow previous = jdbc.query("select setting_key,value_type,value_json::text,is_overridden,version,updated_by,updated_at "
                            + "from telegram_runtime_settings where setting_key=?",
                    rs -> rs.next() ? new RuntimeSettingRow(rs.getString("setting_key"), rs.getString("value_type"),
                            rs.getString("value_json"), rs.getBoolean("is_overridden"), rs.getLong("version"),
                            rs.getLong("updated_by"), rs.getTimestamp("updated_at").toInstant()) : null, key);
            long actualVersion = previous == null ? 0 : previous.version();
            if (actualVersion != expectedVersion) throw new RuntimeSettingVersionConflictException();
            int changed = jdbc.update("""
                    insert into telegram_runtime_settings(setting_key,value_type,value_json,is_overridden,version,updated_by,updated_at)
                    values (?, ?, ?::jsonb, ?, ?, ?, ?)
                    on conflict(setting_key) do update set value_type=excluded.value_type,value_json=excluded.value_json,
                        is_overridden=excluded.is_overridden,version=excluded.version,updated_by=excluded.updated_by,updated_at=excluded.updated_at
                    where telegram_runtime_settings.version=?
                    """, key, type, valueJson, overridden, expectedVersion + 1, actorId, Timestamp.from(at), expectedVersion);
            if (changed != 1) throw new RuntimeSettingVersionConflictException();
            jdbc.update("""
                    insert into telegram_runtime_settings_history(setting_key,version,previous_json,next_json,
                        previous_overridden,next_overridden,actor_user_id,changed_at)
                    values (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                    """, key, expectedVersion + 1, previous == null ? null : previous.valueJson(), valueJson,
                    previous != null && previous.overridden(), overridden, actorId, Timestamp.from(at));
            return new RuntimeSettingRow(key, type, valueJson, overridden, expectedVersion + 1, actorId, at);
        });
    }
}
