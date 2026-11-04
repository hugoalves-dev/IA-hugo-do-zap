package dev.hugo.watcher.telegram;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeSettingsServiceTest {
    private final MemoryRepository repository = new MemoryRepository();
    private final RuntimeSettingsService settings = new RuntimeSettingsService(repository, 24);

    @Test void contextWindowOverrideMustStayWithinConfiguredEnvironmentCeiling() {
        assertThrows(IllegalArgumentException.class,
                () -> settings.set("ai.context_window_hours", "25", 0, 1001, Instant.parse("2026-11-04T12:00:00Z")));
        assertTrue(repository.rows.isEmpty());
    }

    @Test void contextWindowOverrideIncrementsVersionAndCanRestoreEnvironmentValue() {
        var changed = settings.set("ai.context_window_hours", "12", 0, 1001,
                Instant.parse("2026-11-04T12:00:00Z"));
        assertEquals(12, changed.effectiveValue());
        assertEquals("RUNTIME", changed.source());
        assertEquals(1, changed.version());
        assertEquals(12, settings.contextWindowHours());

        var restored = settings.restore("ai.context_window_hours", 1, 1001,
                Instant.parse("2026-11-04T12:01:00Z"));
        assertEquals(24, restored.effectiveValue());
        assertEquals("ENV", restored.source());
        assertEquals(2, restored.version());
        assertEquals(24, settings.contextWindowHours());
    }

    @Test void staleVersionAndUnknownSettingAreRejectedWithoutMutation() {
        settings.set("ai.context_window_hours", "8", 0, 1001, Instant.parse("2026-11-04T12:00:00Z"));
        assertThrows(RuntimeSettingVersionConflictException.class,
                () -> settings.set("ai.context_window_hours", "6", 0, 1001, Instant.parse("2026-11-04T12:01:00Z")));
        assertThrows(IllegalArgumentException.class,
                () -> settings.set("watcher.automation.kill_switch", "false", 1, 1001, Instant.parse("2026-11-04T12:02:00Z")));
        assertEquals(1, repository.rows.get("ai.context_window_hours").version());
    }

    @Test void lowerEnvironmentCeilingClampsAnOlderRuntimeOverrideWithoutBreakingReads() {
        repository.rows.put("ai.context_window_hours", new RuntimeSettingRow("ai.context_window_hours", "INTEGER",
                "18", true, 4, 1001, Instant.parse("2026-11-04T12:00:00Z")));
        var restricted = new RuntimeSettingsService(repository, 12).list().getFirst();
        assertEquals(12, restricted.effectiveValue());
        assertEquals("ENV_CEILING", restricted.source());
        assertTrue(restricted.blockers().contains("runtime_override_above_current_environment_ceiling"));
    }

    static final class MemoryRepository implements RuntimeSettingsRepository {
        private final Map<String, RuntimeSettingRow> rows = new HashMap<>();
        @Override public Map<String, RuntimeSettingRow> findAll() { return Map.copyOf(rows); }
        @Override public RuntimeSettingRow update(String key, String type, String valueJson, boolean overridden,
                                                  long expectedVersion, long actorId, Instant at) {
            long actual = Optional.ofNullable(rows.get(key)).map(RuntimeSettingRow::version).orElse(0L);
            if (actual != expectedVersion) throw new RuntimeSettingVersionConflictException();
            var row = new RuntimeSettingRow(key, type, valueJson, overridden, actual + 1, actorId, at);
            rows.put(key, row);
            return row;
        }
    }
}
