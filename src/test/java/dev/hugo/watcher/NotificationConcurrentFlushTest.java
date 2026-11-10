package dev.hugo.watcher;

import dev.hugo.watcher.notification.DecisionDeliveryCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationConcurrentFlushTest {
    @Test
    void inboundDoesNotSucceedWhileAnotherFlushHasNotCommitted() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return mock(TransactionStatus.class);
        });
        var outbox = mock(NotificationOutboxRepository.class);
        var service = new NotificationService(mock(NotificationQueueQueryRepository.class), manager, outbox,
                mock(TelegramTransport.class), mock(TelegramMessageFormatter.class), new WatcherProperties(),
                Clock.systemUTC(), mock(ArchiveModeService.class), null, mock(DecisionDeliveryCoordinator.class));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var ongoing = executor.submit(() -> service.flush(Instant.now()));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            try {
                assertThrows(IllegalStateException.class, () -> service.flushAfterInbound(Instant.now()));
            } finally {
                release.countDown();
            }
            ongoing.get(5, TimeUnit.SECONDS);
        }
    }
}
