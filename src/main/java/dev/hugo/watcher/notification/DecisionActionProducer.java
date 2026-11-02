package dev.hugo.watcher.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/** Runs inside decision persistence. Shadow mode never creates delivery work. */
@Service
public final class DecisionActionProducer {
    private final DecisionEligibilityReader eligibility;
    private final IntelligentAlertService alerts;
    private final WhatsAppActionService actions;
    private final boolean realMode;

    public DecisionActionProducer(DecisionEligibilityReader eligibility, IntelligentAlertService alerts,
                                  WhatsAppActionService actions,
                                  @Value("${watcher.decision-producer.real-mode-enabled:false}") boolean realMode) {
        this.eligibility = eligibility;
        this.alerts = alerts;
        this.actions = actions;
        this.realMode = realMode;
    }

    public boolean realModeEnabled() { return realMode; }

    public void produce(UUID decisionId) {
        if (!realMode) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("producer requires decision transaction");
        if (!eligibility.isEligible(decisionId)) return;
        alerts.enqueue(decisionId);
        actions.enqueue(decisionId);
    }
}
