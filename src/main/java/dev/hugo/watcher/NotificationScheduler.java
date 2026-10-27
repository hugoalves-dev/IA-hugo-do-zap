package dev.hugo.watcher;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import dev.hugo.watcher.notification.WhatsAppActionDispatcher;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class NotificationScheduler {
    private static final System.Logger LOG = System.getLogger(NotificationScheduler.class.getName());
    private final AtomicBoolean running = new AtomicBoolean();
    private final NotificationService notifications;
    private final ConnectionService connections;
    private final WhatsAppActionDispatcher whatsappActions;

    @Autowired
    public NotificationScheduler(NotificationService notifications, ConnectionService connections,
                                 WhatsAppActionDispatcher whatsappActions) {
        this.notifications = notifications;
        this.connections = connections;
        this.whatsappActions = whatsappActions;
    }

    public NotificationScheduler(NotificationService notifications, ConnectionService connections) {
        this.notifications = notifications;
        this.connections = connections;
        this.whatsappActions = null;
    }

    @Scheduled(fixedDelayString = "${watcher.notification-delay:PT1M}")
    public void flushScheduled() {
        if (!running.compareAndSet(false, true)) return;
        Instant now = Instant.now();
        try {
            connections.flushGracePeriod(now);
            notifications.flush(now);
            if (whatsappActions != null) whatsappActions.dispatchNext();
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.ERROR, "Scheduled notification flush failed: error={0}", ex.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }
}
