package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

public class ArchiveModeService {
    private final ArchiveModeDataRepository data;
    private final ArchiveModeJdbcRepository legacy;
    private final TransactionTemplate transactions;

    public ArchiveModeService(ArchiveModeDataRepository data, PlatformTransactionManager manager) {
        this.data = Objects.requireNonNull(data);
        this.legacy = null;
        this.transactions = new TransactionTemplate(manager);
    }

    /** Standalone test compatibility while callers migrate to Spring-managed repositories. */
    public ArchiveModeService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.data = null;
        this.legacy = new ArchiveModeJdbcRepository(jdbc);
        this.transactions = new TransactionTemplate(manager);
    }

    public ArchiveMode getMode() {
        return ArchiveMode.valueOf(data != null ? data.readMode() : legacy.readMode());
    }

    public ArchiveMode setMode(ArchiveMode mode) {
        Objects.requireNonNull(mode, "mode");
        return Objects.requireNonNull(transactions.execute(status -> {
            int affected = data != null ? data.updateMode(mode.name()) : legacy.updateMode(mode.name());
            if (affected != 1) throw new IllegalStateException("archive mode singleton missing");
            return getMode();
        }));
    }
}
