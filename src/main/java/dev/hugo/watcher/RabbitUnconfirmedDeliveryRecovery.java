package dev.hugo.watcher;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Slow fallback for post-commit jobs whose initial broker handoff was not confirmed. */
@Component
public class RabbitUnconfirmedDeliveryRecovery {
    private static final System.Logger LOG = System.getLogger(RabbitUnconfirmedDeliveryRecovery.class.getName());

    private final NotificationOutboxRepository outbox;

    public RabbitUnconfirmedDeliveryRecovery(NotificationOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${watcher.rabbitmq.delivery-recovery-delay:PT15S}")
    public void recover() {
        try {
            int recovered = outbox.recoverUnconfirmedActions();
            if (recovered > 0) LOG.log(System.Logger.Level.INFO,
                    "RabbitMQ unconfirmed delivery recovery published {0} jobs", recovered);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING,
                    "RabbitMQ unconfirmed delivery recovery failed: error={0}", ex.getClass().getSimpleName());
        }
    }
}
