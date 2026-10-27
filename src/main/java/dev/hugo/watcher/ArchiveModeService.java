package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

@Service
public class ArchiveModeService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ArchiveModeService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    public ArchiveMode getMode() {
        return ArchiveMode.valueOf(jdbc.queryForObject(
                "select mode from archive_monitor_settings where singleton_id=1", String.class));
    }

    public ArchiveMode setMode(ArchiveMode mode) {
        Objects.requireNonNull(mode, "mode");
        return Objects.requireNonNull(transactions.execute(status -> {
            jdbc.update("update archive_monitor_settings set mode=?, updated_at=now() where singleton_id=1", mode.name());
            return getMode();
        }));
    }
}
