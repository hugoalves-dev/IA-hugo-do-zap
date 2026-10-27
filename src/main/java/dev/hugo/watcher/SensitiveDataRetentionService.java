package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
/** Compatibility name retained for existing callers; scheduling lives in {@link RetentionService}. */
public class SensitiveDataRetentionService extends RetentionService {
    public SensitiveDataRetentionService(JdbcTemplate jdbc) {
        super(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()));
    }
}
