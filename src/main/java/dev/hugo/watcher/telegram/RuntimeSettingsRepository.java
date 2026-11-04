package dev.hugo.watcher.telegram;

import java.time.Instant;
import java.util.Map;

public interface RuntimeSettingsRepository {
    Map<String, RuntimeSettingRow> findAll();

    RuntimeSettingRow update(String key, String type, String valueJson, boolean overridden,
                             long expectedVersion, long actorId, Instant at);
}
