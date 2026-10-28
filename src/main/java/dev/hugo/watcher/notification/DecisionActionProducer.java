package dev.hugo.watcher.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;

/** Runs inside decision persistence. Shadow mode never creates delivery work. */
@Service
public final class DecisionActionProducer {
    private final JdbcTemplate jdbc;
    private final IntelligentAlertService alerts;
    private final WhatsAppActionService actions;
    private final boolean realMode;
    public DecisionActionProducer(JdbcTemplate jdbc, IntelligentAlertService alerts, WhatsAppActionService actions,
                                  @Value("${watcher.decision-producer.real-mode-enabled:false}") boolean realMode) {
        this.jdbc=jdbc; this.alerts=alerts; this.actions=actions; this.realMode=realMode;
    }
    public boolean realModeEnabled() { return realMode; }
    public void produce(UUID decisionId) {
        if (!realMode) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("producer requires decision transaction");
        Boolean eligible = jdbc.queryForObject("""
                select exists(select 1 from simulation_decisions d join simulation_ai_evaluations e on e.batch_id=d.batch_id
                where d.batch_id=? and d.production_mode and d.real_action_allowed and d.decision_state='WOULD_ACT'
                  and d.classification_status='CLASSIFIED' and d.classification_confidence>=0.65
                  and e.result->>'available'='true')
                """,Boolean.class,decisionId);
        if (!Boolean.TRUE.equals(eligible)) return;
        alerts.enqueue(decisionId);
        actions.enqueue(decisionId);
    }
}
