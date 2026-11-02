package dev.hugo.watcher.notification;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Non-Spring legacy integration-test adapter; production uses Spring Data JDBC. */
public final class DecisionEligibilityJdbcReader implements DecisionEligibilityReader {
    private final JdbcTemplate jdbc;

    public DecisionEligibilityJdbcReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isEligible(UUID decisionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from simulation_decisions d join simulation_ai_evaluations e on e.batch_id=d.batch_id
                where d.batch_id=? and d.production_mode and d.real_action_allowed and d.decision_state='WOULD_ACT'
                  and d.classification_status='CLASSIFIED' and d.classification_confidence>=0.65
                  and e.result->>'available'='true')
                """, Boolean.class, decisionId));
    }
}
