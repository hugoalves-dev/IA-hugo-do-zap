package dev.hugo.watcher.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/** Durable dispatch admission. Transactions end before HTTP; ambiguity never grants another send. */
public final class DecisionDeliveryCoordinator {
    private final DeliveryAdmissionRepository admission;
    private final TransactionTemplate transactions;

    /** Legacy constructor retained for standalone/integration tests during #24. */
    public DecisionDeliveryCoordinator(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(new DeliveryAdmissionRepository(jdbc), manager);
    }

    public DecisionDeliveryCoordinator(DeliveryAdmissionRepository admission, PlatformTransactionManager manager) {
        this.admission = admission;
        this.transactions = new TransactionTemplate(manager);
    }

    public boolean begin(UUID decisionId, String channel, UUID token, Instant now) {
        if (token == null || !("TELEGRAM".equals(channel) || "WHATSAPP".equals(channel))) return false;
        return Boolean.TRUE.equals(transactions.execute(
                status -> admission.begin(decisionId, channel, token, now)));
    }

    /** Joins an existing ACK transaction. Only its matching dispatched token can settle once. */
    public void finish(UUID decisionId, String channel, UUID token, String outcome, Instant now) {
        transactions.executeWithoutResult(
                status -> admission.finish(decisionId, channel, token, outcome, now));
    }
}
