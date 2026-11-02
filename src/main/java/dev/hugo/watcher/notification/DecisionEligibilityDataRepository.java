package dev.hugo.watcher.notification;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/** Native SQL eligibility gate. No custom save operation is exposed. */
public interface DecisionEligibilityDataRepository
        extends Repository<DecisionEligibilityRow, UUID>, DecisionEligibilityReader {

    @Override
    @Query("""
                select exists(select 1 from simulation_decisions d join simulation_ai_evaluations e on e.batch_id=d.batch_id
                where d.batch_id=:decisionId and d.production_mode and d.real_action_allowed and d.decision_state='WOULD_ACT'
                  and d.classification_status='CLASSIFIED' and d.classification_confidence>=0.65
                  and e.result->>'available'='true')
            """)
    boolean isEligible(@Param("decisionId") UUID decisionId);
}
