package dev.hugo.watcher;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class NotificationScheduler {
    private static final System.Logger LOG = System.getLogger(NotificationScheduler.class.getName());
    private final AtomicBoolean running = new AtomicBoolean();
    private final NotificationService notifications;
    private final ConnectionService connections;

    public NotificationScheduler(NotificationService notifications, ConnectionService connections) {
        this.notifications = notifications;
        this.connections = connections;
    }

    @Scheduled(fixedDelayString = "${watcher.notification-delay:PT1M}")
    public void flushScheduled() {
        if (!running.compareAndSet(false, true)) return;
        Instant now = Instant.now();
        try {
            connections.flushGracePeriod(now);
            notifications.flush(now);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.ERROR, "Scheduled notification flush failed: error={0}", ex.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }
}
