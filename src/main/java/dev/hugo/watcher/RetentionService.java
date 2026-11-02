package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/** Executes retention as one transaction; SQL remains in the repository layer. */
public class RetentionService {
    private final RetentionRepository repository;
    private final TransactionTemplate transactions;

    public RetentionService(RetentionRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Retained for the existing standalone PostgreSQL integration tests. */
    public RetentionService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(new RetentionRepository(jdbc), transactionManager);
    }

    @Scheduled(fixedDelay = 60_000L)
    public void purgeExpired() { purge(Instant.now()); }

    public int purge(Instant now) {
        Integer count = transactions.execute(status -> repository.purgeInTransaction(now));
        return count == null ? 0 : count;
    }
}
